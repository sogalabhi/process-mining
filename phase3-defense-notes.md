# Process Mining — Defense Notes (Phase 3 + Phase 4)

What was built, the decisions behind it, the bugs hit along the way, and the concepts you need to explain in an interview.

---

## 1. What Phase 3 produces

Input: events stored in Postgres (`case_id`, `activity`, `timestamp`).
Output: a **Directly-Follows Graph (DFG)**: which activity directly follows which, how often, plus where cases start and end.

Example (modules.md):

```
Case A: A → B → C → D
Case B: A → B → C → D
Case C: A → B → D

A → B : 3
B → C : 2
C → D : 2
B → D : 1
```

### Endpoints

| Endpoint | Returns | Purpose |
|---|---|---|
| `GET /api/discovery/events` | `List<EventResponse>` | raw events, flattened |
| `GET /api/discovery/traces` | `List<CaseTrace>` | each case with its events (activity + timestamp), sorted |
| `GET /api/discovery/dfg` | `DfgResponse` | the full graph |

### Final `/dfg` shape

```json
{
  "activities": ["Delivered", "Inspected", "Order Created", ...],
  "transitions": [
    { "from": "Payment Received", "to": "Packed", "count": 8 },
    ...
  ],
  "startActivities": { "Order Created": 11 },
  "endActivities": { "Shipped": 4, "Packed": 3, "Order Created": 2, ... }
}
```

---

## 2. The pipeline

(Current version, after the item 31 refactor. Phase 3 originally did group/sort inside `DiscoveryService` on `Map<String, List<Event>>`; that moved to `EventLogService` so Phase 3 and Phase 4 share one data-prep step.)

```
Postgres
   │  EventLogService.loadCaseTraces()
   │     findAllWithProcessCase()     select e from Event e join fetch e.processCase
   ▼
List<Event>
   │  EventLogService.toCaseTraces()
   │     groupingBy(caseId, LinkedHashMap::new, mapping(Event → TraceEvent, toList()))
   │     sort each case by timestamp
   ▼
List<CaseTrace>          ← one sorted, immutable, JPA-free trace per case
   │
   ├─ countTransitions()              directlyFollows(trace.activities()) + map.merge
   ├─ countStartActivities()          trace.first().activity()
   ├─ countEndActivities()            trace.last().activity()
   └─ activities                      flatMap(trace.activities()) → distinct → sorted
   ▼
DfgResponse
```

Code lives in:
- `service/EventLogService.java`: the only analytics-side code that touches the repository; builds `List<CaseTrace>`
- `service/DiscoveryService.java`: DFG logic on `List<CaseTrace>`
- `model/TraceEvent.java`: `record (activity, timestamp)`
- `model/CaseTrace.java`: `record (caseId, events)` + helpers `activities()`, `first()`, `last()`, `duration()`
- `controller/DiscoveryController.java`: thin HTTP layer (one line per endpoint)
- `model/Transition.java`: `record Transition(String from, String to)`, the map key used while counting
- `dto/TransitionResponse.java`: `record (from, to, count)`, the API output for one edge
- `dto/DfgResponse.java`: `record (activities, transitions, startActivities, endActivities)`

---

## 3. Design decisions and why

### Traces are plain data, not entities
(Phase 3 used `Map<String, List<String>>`; item 31 replaced it with `List<CaseTrace>` so timestamps survive for Phase 4. The reasoning is the same.)

A trace in process mining is just the ordered sequence of activity names. The DFG only needs order and names, not timestamps, IDs, or JPA objects.
- Keeps JPA out of the algorithm, so the algorithm is unit-testable without a database.
- Plain strings serialize to JSON with no surprises.

### Group by case *before* sorting
The DFG is defined **within a case**. "B follows A" only means something if both happened to the same order. Sorting all events globally would interleave cases and create fake transitions between unrelated orders. Grouping first also means each sort is over a small list (k events per case) instead of all n.

### Sort once, rely on order everywhere
After sorting, pairs (item 21), start/end (item 25), and later durations (Phase 4) all just read positions:
- start = `trace.getFirst()`, end = `trace.getLast()`, which is O(1)
- without sorting you'd need to scan for min/max timestamps, and you couldn't build pairs at all.

### `Transition` is a `record`
It's used as a `HashMap` key. `HashMap` finds keys via `hashCode()` (which bucket) and then `equals()` (which entry in the bucket). A record generates both **from its fields**, so two `new Transition("A","B")` objects are the same key.
With a plain class you get `Object`'s identity-based versions, every `new Transition(...)` becomes a different key, and every count stays at 1.
Records are also immutable, which a hash key needs: if the fields changed after insertion, the hash would change and the map could never find the entry again.

### Separate `Transition` (model) and `TransitionResponse` (dto)
Different jobs: one is an internal counting key (from, to), the other is API output (from, to, count). Same split as `Event` vs `EventResponse`.

### Activities come from traces, not from transitions
A one-activity trace (e.g. ORDER-102: `["Order Created"]`) produces **zero** transitions. Building nodes from transitions would silently drop activities that only ever occur alone.

### Transitions sorted by count, descending
The most common paths come first, which is what a process analyst looks at. It also replaces `HashMap`'s arbitrary order.
Tiebreaker: `.reversed().thenComparing(from).thenComparing(to)`, so equal counts have a fixed order. `.reversed()` must come before the `thenComparing` calls, or it would flip the alphabetical tiebreakers too.

### Counts are per occurrence, not per case
`merge(transition, 1, Integer::sum)` adds 1 every time the pair appears. One case looping 3× adds 3.
Evidence: `Packed → Inspected = 4` but only 2 cases did it (ORDER-952 contributed 3).
"How many cases did this?" is a different metric (*case frequency*), relevant for Phase 4.

### Loops and self-loops need no special code
`directlyFollows` only looks at neighbours, so:
- loop `Packed → Inspected → Packed` produces arrows in both directions (a cycle in the graph)
- self-loop `Payment Attempt → Payment Attempt` produces `Transition(from == to)`

Decision: **keep** self-loops. They carry real information (retries). Collapsing them would only make sense if they were known to be duplicate logging.

### No empty-trace guard in `getFirst()` / `getLast()`
A case only exists in `toCaseTraces` because it has at least one event, so an empty trace is impossible. If one ever appeared it would mean an upstream bug, and a loud exception is the right outcome.

### No null checks in `toResponse`
`caseId` can't be null:
1. `validateRow` rejects null/blank `case_id` at CSV import (the boundary where untrusted data enters)
2. `ProcessCase.caseId` is `nullable = false`
3. `Event.processCase` is `optional = false` + `@JoinColumn(nullable = false)`
4. `join fetch` is an inner join, so rows with no case are never returned

A mapper is the wrong layer for validation: defaulting a null there hides an integrity bug behind a normal-looking response.
**Caveat:** `ddl-auto: update` does not add NOT NULL to columns that already exist. Verify with `\d process_cases` and `\d events` in psql.

### Controller stays thin; `getTraces()` in the service
`/traces` and `/dfg` both need fetch → group → sort → build. Putting that in one service method means the logic changes in one place, and the controller only does HTTP.

---

## 4. Bugs hit and what they taught

### Bug 1: `LazyInitializationException` on `/traces`
```
Could not initialize proxy [ProcessCase#1] - no session
  at ProcessCase$HibernateProxy.getCaseId(Unknown Source)
  at DiscoveryService.groupEventsByCase
```
**Cause:** `getAllEvents()` used `findAll()`. `Event.processCase` is `FetchType.LAZY`, so each event held a **proxy** instead of a real `ProcessCase`. With `spring.jpa.open-in-view: false`, the session closed when the repository call returned. Calling `getCaseId()` then tried to load the data with no session.

**Why `/events` worked:** it used `findAllWithProcessCase()` (`join fetch`), which loads the case in the same SQL query.

**Fix:** `getAllEvents()` → `findAllWithProcessCase()`.

### Bug 2: returning entities from `/traces`
`Map<String, List<Event>>` → Jackson serializes `Event → processCase → events → Event → …`, which fails with either another lazy-load error (`ProcessCase.events` is lazy) or infinite recursion.

**Lesson:** the container type doesn't matter (List, Map); the **element type** does. Never return entities from a controller. Return DTOs or plain types.

**Fix:** return `Map<String, List<String>>` via `buildTraces`.

### Bug 3: sort step silently dropped
The controller was refactored to `buildTraces(groupEventsByCase(events))`, and the sort call disappeared. Output still *looked* correct because the CSV rows were already in time order and Postgres returned them in insertion order.

**Lesson:** a test with already-sorted input can't prove sorting works. We created `unsorted_events.csv` (shuffled ORDER-900..902) to prove it.

---

## 5. Concepts to be able to explain

### Lazy loading and proxies
- `fetch = LAZY` is a **default**, and a query can override it (`join fetch`).
- With lazy loading, Hibernate puts a generated subclass `ProcessCase$HibernateProxy` into the field. It overrides every getter: on first call it loads the real object through the session, and throws if the session is closed.
- The proxy **does know its id** (it's the FK on the `events` row), so `getId()` works without loading. Any other getter (`getCaseId()`) forces a load.
- Your getter `getProcessCase()` has no logic. The difference is *what object Hibernate put in the field*.
- See it: `System.out.println(event.getProcessCase().getClass().getName())` prints `ProcessCase` with join fetch and `ProcessCase$HibernateProxy$…` with `findAll()`.

### `open-in-view: false`
With it set to `true`, the session stays open through JSON rendering, so the broken version would "work", but it would fire one extra query per case (the **N+1 problem**). Setting it to `false` turns a silent performance bug into a loud error during development.

### How Spring Data repositories work with no implementation
At startup Spring finds interfaces extending `JpaRepository`, generates an implementation object in memory (a JDK proxy, e.g. `jdk.proxy2.$Proxy123`), and registers it as a bean that gets injected into your constructors. Method bodies come from:
1. **Inherited methods** (`findAll`, `save`, `findById`): the real class `SimpleJpaRepository`.
2. **`@Query` methods** (`findAllWithProcessCase`): your JPQL string.
3. **Derived queries** (`findByCaseId`): Spring **parses the method name**: `find` + `By` + `CaseId` becomes `where p.caseId = :caseId`. The return type (`Optional` vs `List`) decides single vs many. A typo like `findByCaseIdd` fails at startup: `No property 'caseIdd' found`.

### Java collections used
- `Collectors.groupingBy(key, LinkedHashMap::new, toList())`: group, keeping insertion order
- `Map.entrySet()` / `Map.Entry`: iterate key-value pairs
- `stream().map(...).toList()`: transform each element
- `flatMap(List::stream)`: flatten a list of lists into one stream
- `map.merge(key, 1, Integer::sum)`: "put 1 if absent, else old + 1"
- `Comparator.comparingInt(...).reversed()` / `.thenComparing(...)`
- `List.of(...)` is **immutable**: sorting it throws `UnsupportedOperationException`

### C++ equivalents
- `Transition` ≈ `std::pair<std::string, std::string>` with named fields
- `counts.merge(t, 1, Integer::sum)` ≈ `counts[{from, to}]++`
- `std::map<pair,int>` works (pair has `operator<`), but `std::unordered_map<pair,int>` doesn't compile (no hash). A Java record gives both `hashCode` and `equals`.
- Record `equals` vs plain-class `equals` ≈ comparing `*a == *b` vs `a == b`
- `.stream().map(f).toList()` ≈ `std::views::transform(f) | std::ranges::to<std::vector>()`

---

## 6. Testing

Unit tests, all passing:
- `DiscoveryServiceTest`: 7 tests on the DFG methods, built from a `trace("1", "A", "B")` helper that makes a `CaseTrace` with one-second-apart timestamps
- `EventLogServiceTest`: 1 test for grouping + sorting, using `ProcessCase.addEvent(...)` to build events without a DB

- Services are created with `new DiscoveryService(null, null)` / `new EventLogService(null)`: no Spring, no Postgres. The algorithm methods never touch the repository. Runs in ~0.1 s.
- The existing `ProcessMiningApplicationTests` is `@SpringBootTest`: it starts the whole app and **needs Postgres**. Run unit tests alone with `./mvnw test -Dtest='DiscoveryServiceTest,EventLogServiceTest'`.

| Test | Proves |
|---|---|
| `singleActivityTraceHasNoPairs` | 1 activity gives 0 pairs |
| `groupsByCaseAndSortsByTimestamp` (EventLogServiceTest) | shuffled input comes out in time order |
| `traceProducesAdjacentPairsInOrder` | n activities give n−1 ordered pairs |
| `countsTransitionsAcrossCases` | the modules.md example (A→B:3, B→C:2, C→D:2, B→D:1) |
| `loopCountsBothDirectionsPerOccurrence` | loops create both arrows; counts are per occurrence |
| `selfLoopIsCountedLikeAnyTransition` | A→A is counted |
| `countsStartAndEndActivities` | first/last per trace; a single-activity case is both start and end |
| `dfgIncludesLoneActivitiesAndSortsTransitionsByCount` | lone activities kept as nodes; sort by count |

Worth doing: break the code on purpose (e.g. `size() - 2` in `directlyFollows`, remove the sort, remove `.reversed()`) and check that the right test fails. A test that has never failed might not be checking anything.

### Manual test data (project root)
- `events.csv`, `example_input.csv`: original in-order data
- `unsorted_events.csv`: ORDER-900..902, rows shuffled → proves sorting
- `loop_events.csv`: ORDER-950..953 → loop, self-loop, multi-loop

```bash
curl -s -X POST http://localhost:8080/api/import -F "file=@loop_events.csv"
curl -s http://localhost:8080/api/discovery/dfg | jq
```

The importer rejects existing case IDs. To re-import, delete the cases first:
```sql
delete from events where process_case_id in (select id from process_cases where case_id like 'ORDER-95%');
delete from process_cases where case_id like 'ORDER-95%';
```

**Sanity check:** the counts in `startActivities` and in `endActivities` must each sum to the number of cases.

---

## 7. Item 30: questions to answer (still open)

(Q1 complexity and Q5 scalability are largely answered in §12, since Phase 3 uses the same loading pipeline. Q2–Q4 are still yours to answer.)

Write your own answers here, then review them.

1. **What's the complexity** of the whole pipeline (fetch → `/dfg`)?
2. **Why do we sort?**
3. **Why do we group by case first?**
4. **What happens with simultaneous timestamps?**
5. **What breaks at 50 million events, and what would you change?**

Related questions for later phases (from today):
- Why `record` for `Transition`? (§3)
- What's lazy loading? What's N+1? (§5)
- JPA vs Hibernate vs Spring Data?
- Why not null-check in the mapper? (§3)

---

## 8. Known loose ends

- `application.properties` and `application.yml` both exist; `.properties` wins on conflicts. Consider merging.
- `ddl-auto: update` doesn't retrofit constraints; verify NOT NULL in psql.
- `toCaseTraces` uses `LinkedHashMap`, but the query has no `ORDER BY`, so case order is whatever Postgres returns.
- `loadCaseTraces()` loads **every** event into memory, which matters for scalability (item 30).

- `/traces` returns `CaseTrace`, an internal type, directly. Safe (no JPA), but adding a field to `CaseTrace` would change the API without anyone deciding it should. A production API would have its own response DTO.

---

# Phase 4 — Process Analytics

Plan (items 31–46) is in `scracthpaf.txt`. Core idea: **build reusable data preparation once → apply different math → return different DTOs.** Every metric is a projection over the same `List<CaseTrace>`.

## 9. Item 31: the shared analytics base ✅

```
EventLogService.loadCaseTraces()  →  List<CaseTrace>
          ↓                                ↓
   DiscoveryService (DFG)        AnalyticsService (metrics)
```

### Why a separate `EventLogService`
Loading the event log is shared by discovery and analytics. If `AnalyticsService` depended on `DiscoveryService` just to get data, analytics would depend on discovery, which is wrong. Both depend on `EventLogService` instead, and it's the only place that calls the repository for them.

### Why `loadCaseTraces()` and `toCaseTraces(List<Event>)` are split
`loadCaseTraces()` is one line of DB access. `toCaseTraces()` is pure logic, so a test can call it with hand-built events and no database.

### Why convert to `TraceEvent` immediately
After `toCaseTraces()` returns, no entity exists downstream: no lazy proxies, no session issues, and every metric is unit-testable with plain records.

### Why `CaseTrace` has helper methods
`activities()`, `first()`, `last()`, `duration()` are needed by almost every metric. Defining them once means no metric recomputes "first event" or "duration" its own way.

### Why the compact constructor copies the list
```java
public CaseTrace { events = List.copyOf(events); }
```
A record only stops its fields from being reassigned. It doesn't stop someone from changing the list the field points to. `List.copyOf` makes an immutable copy, so the "sorted" guarantee can't be broken from outside.

### Why `List<CaseTrace>` and not a `Map`
The caseId is already inside each record, so a map key would store it twice.

### Formula methods take `List<CaseTrace>` as a parameter
They don't load data themselves, for the same reason `buildDfg(traces)` is separate from `getDfg()`:
1. **Testing:** call them directly with `trace(...)` helpers, with no DB and no mocks.
2. **Reuse:** a combined endpoint could load once and pass the same list to every formula.
3. **Single responsibility:** if loading changes (filters, date ranges, pagination), only `EventLogService` changes.

### "Aren't there too many records?"
7 records + 2 entities, but each sits at one boundary:

| Layer | Types | Job |
|---|---|---|
| CSV input | `EventCsvRow` | raw parsed row, before validation |
| Database | `Event`, `ProcessCase` | JPA mapping |
| Algorithms | `TraceEvent`, `CaseTrace`, `Transition` | plain data the math works on |
| API output | `EventResponse`, `TransitionResponse`, `DfgResponse` | JSON shape |

Rule of thumb: a type earns its place if it marks a boundary or carries a guarantee. `CaseTrace` guarantees sorted + immutable. `Transition` guarantees value equality (map key). `TraceEvent` marks the no-JPA boundary. Records cost one line each.
It would become bad with types that rename the same data with no different job, long mapping chains copying data 4–5 times, or a new record per endpoint with identical shapes.
Honest redundancy: `TraceEvent` and `EventResponse` are near-duplicates (internal vs API).

Alternatives rejected: `CaseTrace` holding `List<Event>` (brings back JPA problems); parallel `List<String>` + `List<Instant>` (can get out of sync); untyped maps/arrays (no names, no type safety).

> Interview answer: "Each type lives at one boundary: CSV input, persistence, domain logic, or API output. The domain records are JPA-free and immutable, so analytics can't hit lazy-loading issues and is unit-testable without a database."

## 10. Phase 4 design decisions (decided, before implementation)

The chosen option is the one being built. Alternatives are listed because "why not X?" is the likely follow-up question.

### D1. Durations in JSON → seconds as a whole number
`"avgDurationSeconds": 5400`. The unit goes in the field name. Internally the code uses `Duration` and converts only at the DTO.
- Rejected: minutes as a decimal (floating point); ISO-8601 strings like `"PT1H30M"` (readable, but awkward to sort or chart).

### D2. Median / P90 / P95 → nearest-rank
Sort the durations and take the element at `ceil(p/100 × n) − 1`. The same method is used for median, P90 and P95.
- Why: it always returns a duration that actually occurred, and it's easy to explain. With ~11 cases, interpolation adds little.
- Rejected: linear interpolation (Excel/numpy default), which can return a duration no case ever had.
- P90 means: "90% of cases finished within this time".

### D3. Single-event cases in duration stats → include them
ORDER-102 and ORDER-302 have duration 0. They're included, and `caseCount` is returned with the stats so the reader sees what went in.
- Why: excluding them hides that they exist. Whether a case counts as "done" is a separate question (D6).

### D4. Bottleneck → top N slowest transitions, with a minimum frequency
Rank transitions by average duration, ignore those seen fewer than `minFrequency` times, and return the top `top`. Query params with defaults: `?top=3&minFrequency=2`.
- Why: simple and always returns a result. The frequency filter stops a one-off delay from being called a bottleneck.
- Rejected: a relative threshold (avg > k × overall avg), which adapts but may return nothing.
- Interview talking point, total impact (`avg × frequency`): "a slightly slow step that happens 1000× can cost more than a very slow one that happens twice." Worth knowing as the next improvement.

### D5. Rework → both case-level and activity-level
Definition: **rework = the same activity occurring more than once in one case** (loops and self-loops both count).
Output: rework rate (% of cases with any repeat) + per-activity breakdown (how many cases repeated it). Both come from one pass over the traces.
- Expected on current data: ORDER-950, 951, 952, 953 have rework, so 4/11 ≈ 36%. Repeated activities: `Packed`, `Inspected`, `Payment Attempt`.

### D6. Throughput → caller chooses the completion activity; bucket per day in UTC
`?endActivity=Delivered` counts only cases whose last activity matches. If omitted, every case counts, with completion time = last event. Cases are bucketed by completion day, in UTC.
- Why: the data has stalled cases (ending at `Order Created` or `Packed`). The caller, not the code, decides what "done" means.
- Rejected: counting every case as completed (counts stalled orders as done); hard-coding "Delivered" (puts business rules in code and breaks for any other process).
- UTC avoids timezone surprises. Timezone support is a possible extension.

### D7. API shape → one endpoint per metric
`/api/analytics/variants`, `/durations`, `/transitions`, `/bottlenecks`, `/rework`, `/activities`, `/start-end`, `/throughput`, each with its own small response record.
- Why: each can be verified on its own, and the frontend fetches only what it needs.
- Rejected for now: one big `/summary` (one huge DTO; any change touches everything).
- Known cost (item 46): each endpoint loads all traces separately. A summary endpoint could load once and share the list. Fine at this data size.

### Summary
D1 seconds · D2 nearest-rank · D3 include · D4 top N + min frequency · D5 both · D6 caller-chosen end activity, daily UTC · D7 one endpoint per metric

### Implementation chunks
- **A** Variants: 32–33
- **B** Time analytics: 34–35, 37–39
- **C** Frequency / behaviour: 36, 40–41
- **D** Throughput, API, quality: 42–46

## 11. Phase 4 implementation (items 32–45)

### Files
- `service/AnalyticsService.java`: every metric. Each has a `getX()` entry point (loads traces once) and a pure `computeX(List<CaseTrace>)` formula.
- `controller/AnalyticsController.java`: one endpoint per metric (D7)
- `dto/analytics/`: 8 response records
- `test/.../AnalyticsServiceTest.java`: 11 unit tests, no database

### Endpoints (`/api/analytics/...`)

| Endpoint | Items | Returns |
|---|---|---|
| `/variants` | 32–33 | unique activity sequences with frequency and % of cases, most common first |
| `/durations` | 34–35 | `caseCount`, `stats` (min/avg/median/p90/p95/max seconds), per-case durations (longest first) |
| `/activities` | 36 | per activity: `occurrences`, `cases`, `casePercentage` |
| `/transitions` | 37–38 | per `A → B`: `frequency` + duration stats |
| `/bottlenecks?top=3&minFrequency=2` | 39 | slowest transitions by average, rare ones filtered out |
| `/rework` | 40 | rework rate + per-activity `casesWithRepeat` and `extraOccurrences` |
| `/start-end` | 41 | start and end activities with count and % |
| `/throughput?endActivity=Delivered` | 42 | completed cases per UTC day; no param = every case counts |

### Implementation notes worth explaining

**`List<String>` as a map key (variants).** `Map<List<String>, Integer>` works because `List.equals`/`hashCode` compare contents, just like a record. Two cases with the same activity sequence land on the same key. The key is the unmodifiable list from `trace.activities()`, which matters: a key that changes after insertion can't be found again.

**Occurrences vs cases (activities).** Occurrences count every appearance. Cases count each case once, done by looping over `new HashSet<>(trace.activities())`, which removes duplicates within the case. This is the per-occurrence vs per-case distinction from Phase 3, now exposed as two numbers.

**`computeIfAbsent` (transition durations).** `durations.computeIfAbsent(key, k -> new ArrayList<>()).add(seconds)` means "get the list for this key, creating an empty one first if it doesn't exist, then add to it". It's the list version of `merge`.

**Transition durations reuse the DFG's pairing.** Same neighbour walk as `directlyFollows`, but over `TraceEvent`s so the timestamps are available. The `Transition` record is reused as the map key.

**Bottlenecks are built on transition stats.** `computeBottlenecks` = `computeTransitionStats` → filter by frequency → sort by average → limit. No separate duration logic.

**One `DurationStats` record for cases and transitions.** Same six numbers, same nearest-rank code (`durationStats`, `percentile`). The count lives on the owner (`caseCount`, `frequency`), not inside the stats.

**`durationStats` returns null for an empty list.** With no data there are no stats to report. `/durations` on an empty database returns `"stats": null`. Transitions always have at least one duration, so it can't happen there.

**Nested records.** Small types used by only one response (`CaseDuration`, `ActivityRework`, `ActivityShare`, `DailyCount`) are declared inside that response record. This keeps the file count down and signals that they aren't shared.

**Percentages** are rounded to 2 decimals by one helper (`3 of 11 → 27.27`). A total of 0 returns 0 instead of dividing by zero.

**Throughput day buckets** use `LocalDate.ofInstant(ts, ZoneOffset.UTC)` and a `TreeMap`, so days come out in date order.

**Every sort has a tiebreaker**, so the output order is always the same.

### Tests (`AnalyticsServiceTest`, 11)
Variants + percentages, nearest-rank percentiles on 1..10 minutes (median 300, p90 540, p95 600), single-value stats, single-event case = 0 s, occurrences vs cases, transition durations, bottleneck filtering and `top`, rework case + activity counts, start/end shares, throughput with and without `endActivity` across two UTC days, percentage rounding and total 0.
Helpers: `trace("1", "A", "B")` (one minute apart) and `trace("1", at("A", 0), at("B", 30))` (explicit minutes).

### Known limits (for item 46 and Phase 6)
- Every endpoint calls `loadCaseTraces()` separately, so the whole event table is loaded once per request.
- No validation on `top` / `minFrequency`: `top=-1` makes `limit()` throw, which becomes a 500. That's Phase 6 hardening.
- Nothing is filtered by date range. Every metric runs over the whole log.

## 12. Item 46: complexity and scalability (reference answers)

Read these, then try to explain each answer out loud without looking. Being able to say it in your own words is the goal.

**Notation**
- **n** = number of events, **c** = number of cases, **k** = events per case (so n = c × k)
- **a** = distinct activities, **t** = distinct transitions, **v** = distinct variants, **d** = distinct days
- In real logs, k, a, t and d are small (tens to hundreds). n and c are what grow.

### Q1. Time complexity: which step dominates?

**Shared loading step (every endpoint pays this):**

| Step | Cost | Why |
|---|---|---|
| DB query + transfer | O(n) | every event row is read and sent to Java |
| Hibernate builds entities | O(n) | one `Event` object per row |
| `groupingBy` into cases | O(n) | one hash lookup per event |
| `Event` → `TraceEvent` | O(n) | one small object per event |
| sort each case by time | O(n log k) | c sorts of k items each: c × k log k = n log k |

Since k is small, sorting is close to O(n). The worst case is one huge case (k = n), which costs O(n log n).

**Each metric, after loading:**

| Metric | Cost | Dominant part |
|---|---|---|
| Variants | O(n + v log v) | building and hashing each activity list touches every event |
| Case durations | O(c log c) | sort cases by duration; `duration()` is O(1) per case (first/last) |
| Activity frequencies | O(n + a log a) | one pass, plus a per-case `HashSet` |
| Transition stats | O(n log n) worst | one pass for pairs, then sorting each transition's durations for percentiles |
| Bottlenecks | same as transition stats + O(t log t) | built on transition stats |
| Rework | O(n) | per-case counts |
| Start/end | O(c) | first/last per case |
| Throughput | O(c log d) | one `TreeMap` insert per case |

**What dominates in practice:** not the math, but **loading**. Every request pulls all n rows from Postgres, creates n entities, then n records. The CPU work of the formulas is tiny next to the I/O and object creation. The big-O is roughly O(n) or O(n log n) either way, but the constant factor of "DB → JDBC → Hibernate → records" is large.

### Q2. Memory cost of `List<CaseTrace>`

All n events are held in memory at once, and during loading **several copies** exist at the same time:

1. **JDBC result set:** by default the Postgres driver reads the **entire result set into memory** before returning the first row. It only streams if you set a fetch size and turn autocommit off.
2. **`Event` entities + the persistence context:** Hibernate also tracks every loaded entity.
3. **`TraceEvent` records + `CaseTrace` lists:** the copy analytics actually uses.

Rough cost per event: an `Event` object, an `Instant`, an activity `String` (a separate copy per row: `"Packed"` loaded a million times is a million strings), plus the `TraceEvent` and list slots. **Roughly 200–300 bytes per event at the peak.** That's an estimate, not a measurement, but the order of magnitude is what matters.

| Events | Approx. peak memory |
|---|---|
| 10,000 | ~3 MB, trivial |
| 1 million | ~250 MB, noticeable |
| 50 million | **~12+ GB**, beyond a typical JVM heap |

Transition stats add more: a boxed `Long` per consecutive pair, which is another ~n × 16+ bytes.

So **memory is O(n)**, and it grows with the whole event log, not with the size of the answer. The answer (for example 7 activity counts) is tiny, but computing it needs everything in memory.

### Q3. At 50 million events, what breaks first?

In rough order:

1. **`/api/discovery/events` and `/traces`** break first. They don't just load n events, they **send all n back as JSON**. That's gigabytes of response, and the serialization alone would run out of memory or time out.
2. **Every analytics endpoint and `/dfg`** break next. Each calls `loadCaseTraces()`, which needs ~12 GB (Q2), so the result is an `OutOfMemoryError`, or at best minutes per request.
3. **Concurrent requests multiply it.** Two users opening the dashboard means two full copies of the log in memory, because each endpoint loads separately (decision D7's known cost).
4. **`/durations`** also returns one entry **per case**. At millions of cases, that response is huge even though the stats part is 6 numbers.

What does **not** break: the algorithms themselves. O(n log k) for 50M events is seconds of CPU. **The design problem is data movement, not math.**

### Q4. What could move into SQL?

Almost everything. Postgres can compute each metric where the data already lives and send back only the small result.

| Metric | SQL approach |
|---|---|
| Activity frequencies | `select activity, count(*), count(distinct process_case_id) from events group by activity` |
| Case durations | `group by process_case_id` with `max(timestamp) - min(timestamp)` |
| Percentiles | `percentile_disc(0.9) within group (order by duration)`. `percentile_disc` returns an **actual value** from the data, which matches decision D2 (nearest-rank) |
| Transitions + durations | window function: `lead(activity) over (partition by process_case_id order by timestamp)` and `lead(timestamp) ...`, then `group by activity, next_activity` with `count(*)`, `avg(...)` |
| Start / end | `row_number() over (partition by process_case_id order by timestamp)`, keep row 1 (start) or the last row (end) |
| Variants | `string_agg(activity, ' → ' order by timestamp)` per case, then `group by` that string |
| Rework | `group by process_case_id, activity having count(*) > 1` |
| Throughput | per case `max(timestamp)`, then `group by date_trunc('day', ... at time zone 'UTC')` |

These all need an **index on `events(process_case_id, timestamp)`** so Postgres can read each case's events in order without a full sort.

**What it costs:**
- **Testability:** the Java formulas are unit-tested in milliseconds with `trace("1", "A", "B")`. SQL logic needs a real Postgres to test (e.g. Testcontainers), which is slower and more setup.
- **Readability:** window functions and `percentile_disc` are harder to read and review than a Java loop. The logic ends up in query strings instead of typed code.
- **Portability:** `percentile_disc`, `string_agg` and `date_trunc` are Postgres-specific.
- **Duplication risk:** if both a Java and an SQL version exist, they can drift apart.

**A middle ground (cheap, keeps the Java code):**
- **Projection query:** select only what analytics needs straight into a record, skipping entities and the persistence context:
  `select new com.abhi.processmining.model.TraceEventRow(pc.caseId, e.activity, e.timestamp) from Event e join e.processCase pc order by pc.caseId, e.timestamp`
  This removes one of the three in-memory copies from Q2, and the database does the sorting.
- **Streaming:** read rows **ordered by case**, build one `CaseTrace` at a time, update running aggregates, then discard it. Memory becomes O(one case + the aggregates) instead of O(n). The catch: exact percentiles need all the values, so they either still keep c durations (fine, much smaller than n) or use an approximate algorithm (e.g. t-digest).

### Q5. When to precompute or cache

**Precompute or cache when data changes rarely compared to how often it's read.** That's exactly this project:
- Data changes only on **CSV import** (rare, batch).
- Metrics are read on every dashboard view (often).
- Every metric currently recomputes from scratch, even though nothing changed.

**Options, from simplest:**
1. **One load per request for a summary endpoint:** load traces once and compute all metrics from the same list. This fixes the "each endpoint loads separately" cost of D7.
2. **Application cache:** Spring `@Cacheable` on the `getX()` methods, cleared (`@CacheEvict`) when an import finishes. The first request after an import pays the cost, and later ones are instant.
3. **Precompute at import time:** calculate the metrics right after `importRows` and store them in tables. Reads become simple `select`s.
4. **Postgres materialized views** of the SQL from Q4, refreshed after each import.

**When *not* to cache:**
- Data arrives **continuously** and users need real-time numbers. The cache would always be stale.
- Queries have **many filter combinations** (date ranges, per-customer, per-region). You can't precompute every combination, so pushing the work into SQL (Q4) scales better.
- The data is small. Here, at ~40 events, caching would add complexity for no gain. It becomes worth it once loading takes noticeable time.

### One-paragraph interview answer

> Every metric is linear or n log n in the number of events, so the algorithms aren't the problem. The problem is that each request loads the entire event log into JVM memory, with several copies (JDBC, entities, records), at roughly a few hundred bytes per event. That's fine at thousands of events and breaks at tens of millions. The fixes, in order of effort: project straight into records and stream rows ordered by case so memory stays per-case; push aggregations into Postgres with GROUP BY and window functions over an index on (case, timestamp), and send back only the small result; and since the log only changes on import, cache or precompute metrics and invalidate them after each import. The trade-off is that SQL-side logic is harder to unit-test and Postgres-specific, which is why the in-memory Java version was the right starting point.

## 13. Walkthrough: analytics helpers and counting formulas (Steps 0–2)

### Practice log (times in minutes from the case's first event)

```
Case 1: A@0  B@10  C@40
Case 2: A@0  B@10  C@20
Case 3: A@0  B@20  B@30  C@60
Case 4: A@0  C@5
```

### Step 0: how a request reaches the formulas

Example: `GET /api/analytics/variants`

```
AnalyticsController.getVariants()                          controller/AnalyticsController.java
        ↓
AnalyticsService.getVariants()                             AnalyticsService.java:29
        ↓
eventLogService.loadCaseTraces()                           EventLogService.java:21
        ↓   1. repository fetches every Event row from Postgres
        ↓   2. groups them by caseId
        ↓   3. converts each Event → TraceEvent(activity, timestamp)
        ↓   4. sorts each case's events by timestamp
        ↓   returns List<CaseTrace>
        ↓
computeVariants(traces)                                    AnalyticsService.java:63   ← the formula
        ↓   calls percentage(...)                                                     ← a helper
        ↓
List<VariantResponse>  →  Spring turns it into JSON
```

Every endpoint takes the same path. Only the `computeXxx` method at the end changes.

Why each `getXxx` / `computeXxx` pair is split:
- **`getXxx()`** loads from the database, then calls the formula.
- **`computeXxx(List<CaseTrace>)`** is pure math with no database access.
- `AnalyticsServiceTest` calls `computeXxx` directly with hand-built traces, so the tests don't need Postgres.

After `loadCaseTraces()`, the practice log looks like this in memory:

```
[
  CaseTrace("1", [A@0,  B@10, C@40]),
  CaseTrace("2", [A@0,  B@10, C@20]),
  CaseTrace("3", [A@0,  B@20, B@30, C@60]),
  CaseTrace("4", [A@0,  C@5])
]
```

Each case also offers these helpers (`model/CaseTrace.java`):
- `trace.activities()` gives the names only, e.g. `[A, B, B, C]` for case 3.
- `trace.first()` and `trace.last()` give the first and last event.
- `trace.duration()` is the last timestamp minus the first.

---

### Step 1: the shared helpers

These four small methods sit at the bottom of `AnalyticsService` (lines 262–307). None of them is an endpoint. The formulas call them so the same math isn't written eight times.

#### Where each helper is used

| Helper | Called from | Line |
|---|---|---|
| `percentage` | `computeVariants` | 74 |
| | `computeActivityFrequencies` | 117 |
| | `computeRework` | 214 |
| | `shares` | 303 |
| `shares` | `computeStartEnd` (for both starts and ends) | 232, 233 |
| `durationStats` | `computeCaseDurations` | 94 |
| | `computeTransitionStats` (so bottlenecks use it too) | 153 |
| `percentile` | only inside `durationStats` | 280–282 |

```
percentage ◄── variants, activity frequencies, rework, shares
shares     ◄── start-end
durationStats ◄── case durations, transition stats (◄── bottlenecks)
   └── percentile
```

#### 1a. `percentage(part, total)` (line 294)

```java
static double percentage(int part, int total) {
    if (total == 0) {
        return 0;
    }
    return Math.round(part * 10000.0 / total) / 100.0;
}
```

It answers "what percent of `total` is `part`, rounded to 2 decimals?"

Worked example, `percentage(3, 11)`:

| Step | Value | Why |
|---|---|---|
| `3 * 10000.0` | 30000.0 | ×100 turns it into a percent; another ×100 keeps 2 decimals |
| `/ 11` | 2727.2727… | |
| `Math.round(...)` | 2727 | drops everything after 2 decimals, with proper rounding |
| `/ 100.0` | **27.27** | shifts the 2 decimals back |

Two details:
- `10000.0` is a `double`. If you wrote `10000`, then `3 * 10000 / 11` would be **integer division** and give `2727` with no rounding. Using a `double` avoids that.
- The `total == 0` guard stops a divide-by-zero when the database is empty (no cases).

#### 1b. `percentile(sorted, p)` (line 288)

```java
static long percentile(List<Long> sorted, int p) {
    int rank = (int) Math.ceil(p / 100.0 * sorted.size());
    return sorted.get(Math.max(rank, 1) - 1);
}
```

It answers "what value is p% of the way through this **sorted** list?" This is the nearest-rank method (decision D2).

With the practice log's case durations in seconds, `sorted = [300, 1200, 2400, 3600]` and n = 4:

| p | `p/100 × n` | `ceil` = rank | index (rank − 1) | result |
|---|---|---|---|---|
| 50 | 2.0 | 2 | 1 | **1200** |
| 90 | 3.6 | 4 | 3 | **3600** |
| 95 | 3.8 | 4 | 3 | **3600** |

Things to understand here:
- **The input must already be sorted.** `percentile` doesn't sort. `durationStats` sorts first, then calls it.
- **`rank` counts from 1, `get()` counts from 0.** That is why the code subtracts 1.
- **`Math.max(rank, 1)` is a safety guard.** If p were 0, rank would be 0 and `get(-1)` would crash. The code only passes 50, 90 and 95, so the guard never actually fires here.
- **The result is always a real value from the list.** Interpolation would give something like 1800 for the median, which no case ever took. This is decision D2.
- **Reading it:** "P90 = 3600s" means 90% of cases finished in 3600s or less.

#### 1c. `durationStats(seconds)` (line 265)

```java
static DurationStats durationStats(List<Long> seconds) {
    if (seconds.isEmpty()) return null;

    List<Long> sorted = seconds.stream().sorted().toList();   // sort once

    long sum = 0;
    for (long value : sorted) sum += value;

    return new DurationStats(
        sorted.getFirst(),                          // min: first after sorting
        Math.round((double) sum / sorted.size()),   // avg
        percentile(sorted, 50),                     // median
        percentile(sorted, 90),
        percentile(sorted, 95),
        sorted.getLast()                            // max: last after sorting
    );
}
```

It turns any list of durations into one summary object with six numbers. The output shape is `dto/analytics/DurationStats.java`.

Worked example with input `[2400, 1200, 3600, 300]` (the case durations, unsorted):

| Field | Value |
|---|---|
| sorted | [300, 1200, 2400, 3600] |
| min | 300 |
| avg | 7500 / 4 = **1875** |
| median | 1200 |
| p90 | 3600 |
| p95 | 3600 |
| max | 3600 |

Why it's a shared helper: two unrelated metrics need the same six numbers.
- **Case durations:** one number per case, so "how long do cases take?"
- **Transition durations:** one number per A→B hop, so "how long does A→B take?"

Details:
- **Sorting once pays for four answers.** After sorting, min is the first element, max is the last, and all three percentiles can be read straight off it.
- **`sum` is a `long`.** Adding many large second counts could overflow an `int`.
- **`(double) sum`:** without the cast, `7500 / 4` would be integer division. That happens to be exact here, but `7 / 2` would give 3 instead of 4.
- **It returns `null` when the list is empty.** An empty list means no data, and a min of 0 would be a lie. This only happens when there are no cases at all.

#### 1d. `shares(counts, total)` (line 301), private

```java
private static List<ActivityShare> shares(Map<String, Integer> counts, int total) {
    return counts.entrySet().stream()
            .map(entry -> new ActivityShare(entry.getKey(), entry.getValue(),
                                            percentage(entry.getValue(), total)))
            .sorted(Comparator.comparingInt(ActivityShare::count).reversed()
                    .thenComparing(ActivityShare::activity))
            .toList();
}
```

It turns a count map like `{A: 4}` into a sorted list like `[ActivityShare("A", 4, 100.0)]`.

`computeStartEnd` has to do this twice, once for starts and once for ends, so the logic was pulled out into a helper.

Each map entry goes through these steps:
- `entrySet().stream()` yields one `(key, value)` pair at a time, e.g. `("A", 4)`.
- `.map(...)` turns each pair into an `ActivityShare(activity, count, percentage)`.
- `.sorted(...)` puts the biggest count first. On a tie, it sorts alphabetically by name.

The tie-break matters because **`HashMap` has no order**. Without it, the same data could come back in a different order on each run, and tests couldn't compare against a fixed expected list. Every formula in the file ends with this same "sort by count, then by name" pattern for the same reason.

---

### Step 2: the counting formulas

All four follow the same pattern:

```
1. loop over every CaseTrace
2. count something into a HashMap, using merge(key, 1, Integer::sum)
3. stream the map → build response DTOs → sort → return
```

#### First, understand `map.merge(key, 1, Integer::sum)`

It's the most important line in the whole file. It means:
- if `key` isn't in the map yet, put `1`
- if it is, replace the old value with `old + 1`

Example:
```java
counts.merge("A", 1, Integer::sum);   // {A=1}
counts.merge("A", 1, Integer::sum);   // {A=2}
counts.merge("B", 1, Integer::sum);   // {A=2, B=1}
```

`Integer::sum` is shorthand for `(old, add) -> old + add`. It does the same job as C++'s `counts[key]++`.

#### 2a. Start/end activities (item 41): `computeStartEnd`, line 221

**Question it answers:** how do cases begin and end?

```java
Map<String, Integer> starts = new HashMap<>();
Map<String, Integer> ends = new HashMap<>();

for (CaseTrace trace : traces) {
    starts.merge(trace.first().activity(), 1, Integer::sum);
    ends.merge(trace.last().activity(), 1, Integer::sum);
}
return new StartEndResponse(traces.size(), shares(starts, ...), shares(ends, ...));
```

Trace by hand:

| Case | `first().activity()` | `last().activity()` | starts | ends |
|---|---|---|---|---|
| 1 | A | C | {A=1} | {C=1} |
| 2 | A | C | {A=2} | {C=2} |
| 3 | A | C | {A=3} | {C=3} |
| 4 | A | C | {A=4} | {C=4} |

`shares` then converts `{A=4}` to `[A, 4, 100.0]` and `{C=4}` to `[C, 4, 100.0]`.

JSON:
```json
{ "caseCount": 4,
  "startActivities": [{"activity":"A","count":4,"percentage":100.0}],
  "endActivities":   [{"activity":"C","count":4,"percentage":100.0}] }
```

**Why it matters:** if 3% of cases *end* at "Payment Received" instead of "Delivered", those cases are stuck or abandoned.

`first()` and `last()` only give the true start and end because `EventLogService` already sorted each case by time. Without that sort, this formula would be wrong.

#### 2b. Variants (items 32–33): `computeVariants`, line 63

**Question it answers:** which distinct paths do cases take through the process, and how often?

```java
Map<List<String>, Integer> counts = new HashMap<>();
for (CaseTrace trace : traces) {
    counts.merge(trace.activities(), 1, Integer::sum);
}
```

**The key idea: the map key is the entire activity list.** Two cases belong to the same variant if their activity sequences are identical.

Trace by hand:

| Case | `activities()` | counts after |
|---|---|---|
| 1 | [A, B, C] | {[A,B,C]=1} |
| 2 | [A, B, C] | {[A,B,C]=**2**} |
| 3 | [A, B, B, C] | {[A,B,C]=2, [A,B,B,C]=1} |
| 4 | [A, C] | {[A,B,C]=2, [A,B,B,C]=1, [A,C]=1} |

**Why case 2 finds case 1's entry:** case 2's `activities()` builds a **new** list object, a different object from case 1's. `HashMap` still matches them, because Java's `List.equals()` and `List.hashCode()` compare **contents**, element by element, not object identity. That is why a `List<String>` can be a map key.

Then comes the stream (lines 70–78):
```java
.map(entry -> new VariantResponse(entry.getKey(), entry.getValue(),
                                  percentage(entry.getValue(), traces.size())))
```

| Variant | frequency | `percentage(freq, 4)` |
|---|---|---|
| [A,B,C] | 2 | 50.0 |
| [A,B,B,C] | 1 | 25.0 |
| [A,C] | 1 | 25.0 |

**Sorting:**
- Highest frequency comes first.
- On a tie, the code joins each list into a string like `"A → B → B → C"` and compares alphabetically.
- `"A → B → B → C"` comes before `"A → C"` because `B` < `C`. So [A,B,B,C] is listed before [A,C].

**Why it matters:** the top variant is the "happy path". A long tail of rare variants means the process is messy.

#### 2c. Activity frequencies (item 36): `computeActivityFrequencies`, line 99

**Question it answers:** how often does each activity happen? It keeps two separate counts.

```java
for (CaseTrace trace : traces) {
    for (String activity : trace.activities()) {
        occurrences.merge(activity, 1, Integer::sum);          // every appearance
    }
    for (String activity : new HashSet<>(trace.activities())) {
        cases.merge(activity, 1, Integer::sum);                // once per case
    }
}
```

**The trick is `new HashSet<>(...)`.** A set drops duplicates, so case 3's `[A, B, B, C]` becomes `{A, B, C}`. B is counted once for that case, not twice.

Trace by hand:

| Case | activities | HashSet | occurrences after | cases after |
|---|---|---|---|---|
| 1 | A,B,C | {A,B,C} | A1 B1 C1 | A1 B1 C1 |
| 2 | A,B,C | {A,B,C} | A2 B2 C2 | A2 B2 C2 |
| 3 | A,B,**B**,C | {A,B,C} | A3 **B4** C3 | A3 **B3** C3 |
| 4 | A,C | {A,C} | A4 B4 C4 | A4 B3 C4 |

Final output, with the percentage computed as `percentage(cases, 4)`:

| activity | occurrences | cases | casePercentage |
|---|---|---|---|
| A | 4 | 4 | 100.0 |
| B | 4 | **3** | **75.0** |
| C | 4 | 4 | 100.0 |

**Why keep both counts:** B has 4 occurrences but appears in only 3 cases. The difference comes from a repeat, which is the signal rework analysis (next) looks at. Occurrences alone would suggest B happens everywhere, when case 4 actually skipped it.

**Why `cases.get(...)` is never null:** every activity that entered `occurrences` also entered `cases` in the same pass through the loop.

#### 2d. Rework (item 40): `computeRework`, line 176

**Question it answers:** how often is work repeated inside a single case? Examples are re-inspecting a product or re-submitting a form.

The structure has two levels, matching decision D5 (both case-level and activity-level):

```java
int casesWithRework = 0;                              // case-level
Map<String, Integer> casesWithRepeat = new HashMap<>();   // activity-level
Map<String, Integer> extraOccurrences = new HashMap<>();  // activity-level

for (CaseTrace trace : traces) {
    // (1) count activities inside THIS case only
    Map<String, Integer> countsInCase = new HashMap<>();
    for (String activity : trace.activities()) {
        countsInCase.merge(activity, 1, Integer::sum);
    }

    // (2) anything counted more than once is rework
    boolean hasRework = false;
    for (entry : countsInCase) {
        if (entry.getValue() > 1) {
            hasRework = true;
            casesWithRepeat.merge(activity, 1, Integer::sum);
            extraOccurrences.merge(activity, count - 1, Integer::sum);
        }
    }
    if (hasRework) casesWithRework++;
}
```

Note that `countsInCase` is created **inside** the loop, so it starts fresh for every case. That is what makes the count per-case. If it were created outside the loop, B in case 1 plus B in case 2 would look like a repeat.

Trace by hand:

| Case | countsInCase | any > 1? | casesWithRework | casesWithRepeat | extraOccurrences |
|---|---|---|---|---|---|
| 1 | {A1, B1, C1} | no | 0 | {} | {} |
| 2 | {A1, B1, C1} | no | 0 | {} | {} |
| 3 | {A1, **B2**, C1} | **B** | 1 | {B=1} | {B=**1**} |
| 4 | {A1, C1} | no | 1 | {B=1} | {B=1} |

**Why `count - 1`:** the first B is normal work, and only the extra ones are rework. B×2 gives 1 extra; B×3 in one case would give 2 extra.

**Why `hasRework` is a boolean flag:** a case where both B and C repeat should still add only **1** to `casesWithRework`. The flag makes sure the case is counted once, however many activities repeat in it.

JSON:
```json
{ "totalCases": 4, "casesWithRework": 1, "reworkPercentage": 25.0,
  "activities": [{"activity":"B","casesWithRepeat":1,"extraOccurrences":1}] }
```

---

### Summary

| Step 2 formula | Counts into | Map key | Helper used |
|---|---|---|---|
| start/end | 2 maps | `first()` / `last()` activity | `shares` → `percentage` |
| variants | 1 map | the **whole activity list** | `percentage` |
| activity frequencies | 2 maps | activity (the second map via `HashSet`) | `percentage` |
| rework | a map per case, plus 2 totals | activity, **within one case** | `percentage` |

All four depend on the same assumption: `EventLogService` has already grouped events by case and sorted them by time. The formulas never sort events themselves.

**Self-check:** add a case 5, `A@0 B@5 C@10 B@15 C@20`, and redo all four tables by hand before running anything.

**Next:** Step 3 (case durations, transition durations, transition stats, bottlenecks, throughput), which is where `durationStats` and `percentile` come in.

## 14. Every analytics metric: meaning and formula

A variant isn't a pair. A variant is the **whole path** a case takes, from its first event to its last. Pairs (A→B) are a different metric, called **transitions**.

```
Case 3:  A → B → B → C

Variant:      [A, B, B, C]           ← the whole path, once per case
Transitions:  A→B, B→B, B→C          ← each step between two neighbours
```

Every metric below uses the same practice log as §13 (times in minutes):

```
Case 1: A@0  B@10  C@40
Case 2: A@0  B@10  C@20
Case 3: A@0  B@20  B@30  C@60
Case 4: A@0  C@5
```

---

### 1. Variants: "Which paths do cases take?"

**Meaning:** cases with exactly the same sequence of activities belong to one variant.

**Formula:**
```
frequency(variant)  = number of cases with that exact path
percentage(variant) = frequency / total cases × 100
```

| Variant | Cases | Frequency | % |
|---|---|---|---|
| A → B → C | 1, 2 | 2 | 2/4 = **50%** |
| A → B → B → C | 3 | 1 | 25% |
| A → C | 4 | 1 | 25% |

**Use:** the top variant is the "normal" path. Many rare variants mean a messy process.

---

### 2. Case duration: "How long does one case take?"

**Formula:**
```
case duration = timestamp of last event − timestamp of first event
```

| Case | Last − first | Seconds |
|---|---|---|
| 1 | 40 − 0 = 40 min | 2400 |
| 2 | 20 − 0 = 20 min | 1200 |
| 3 | 60 − 0 = 60 min | 3600 |
| 4 | 5 − 0 = 5 min | 300 |

---

### 3. Duration statistics: "Summarise many durations as a few numbers"

First sort the durations: `[300, 1200, 2400, 3600]`, so n = 4.

| Stat | Formula | Result |
|---|---|---|
| min | first value after sorting | 300 |
| max | last value after sorting | 3600 |
| average | sum / n = 7500 / 4 | **1875** |
| median (P50) | value at position ⌈0.50 × n⌉ = ⌈2⌉ = 2nd | **1200** |
| P90 | value at position ⌈0.90 × n⌉ = ⌈3.6⌉ = 4th | 3600 |
| P95 | value at position ⌈0.95 × n⌉ = ⌈3.8⌉ = 4th | 3600 |

⌈ ⌉ means "round up".

**What P90 = 3600 means:** 90% of cases finish within 3600 seconds.

**Why report the median and not only the average:** one very slow case pulls the average up. The median doesn't move much. For example, in [1, 1, 1, 100] the average is 25.75 but the median is 1.

---

### 4. Activity frequency: "How often does each activity happen?"

This metric has two counts.

**Formula:**
```
occurrences(X)    = total times X appears, across all cases
cases(X)          = number of cases where X appears at least once
case percentage   = cases(X) / total cases × 100
```

| Activity | Occurrences | Cases | Case % |
|---|---|---|---|
| A | 4 | 4 | 100% |
| B | 4 (1+1+**2**+0) | **3** (cases 1, 2, 3) | **75%** |
| C | 4 | 4 | 100% |

B has 4 occurrences but appears in only 3 cases. The gap is case 3 doing B twice, and case 4 skipping B entirely.

---

### 5. Transition durations and transition statistics: "How long does each step take?"

This is where pairs come in. A **transition** is two events that sit next to each other in one case.

**Formula:**
```
transition duration = timestamp(next event) − timestamp(this event)
frequency(A→B)      = how many times A is directly followed by B
stats(A→B)          = min / avg / median / P90 / P95 / max of all A→B durations
```

Every pair, listed case by case:

| Case | Pairs (duration) |
|---|---|
| 1 | A→B (10m), B→C (30m) |
| 2 | A→B (10m), B→C (10m) |
| 3 | A→B (20m), B→B (10m), B→C (30m) |
| 4 | A→C (5m) |

Grouped by transition:

| Transition | Durations | Frequency | Average |
|---|---|---|---|
| A→B | 10, 10, 20 min | 3 | 13.3 min (800s) |
| B→C | 30, 10, 30 min | 3 | 23.3 min (1400s) |
| B→B | 10 min | 1 | 10 min (600s) |
| A→C | 5 min | 1 | 5 min (300s) |

The counts here match the DFG from Phase 3. This metric adds how long each step takes.

---

### 6. Bottlenecks: "Which step is the slowest?"

**Formula:**
```
1. take the transition statistics
2. drop transitions with frequency < minFrequency   (default 2)
3. sort by average duration, highest first
4. keep the first `top`                              (default 3)
```

With the defaults:
- B→B and A→C are dropped, because they happened only once.
- The result is **B→C (1400s)**, then A→B (800s). **B→C is the bottleneck.**

**Why drop rare transitions:** one strange case that took 3 days would otherwise be reported as the bottleneck. A bottleneck should be something that is slow *repeatedly*.

---

### 7. Rework: "How often is work repeated?"

**Formula:**
```
a case has rework      if any activity appears more than once in that case
rework %               = cases with rework / total cases × 100

per activity X:
  casesWithRepeat(X)   = number of cases where X appears more than once
  extraOccurrences(X)  = sum over those cases of (count of X − 1)
```

In the practice log, case 3 has B twice. Every other case has no repeats.

| Result | Value |
|---|---|
| cases with rework | 1 |
| rework % | 1/4 = **25%** |
| B: casesWithRepeat | 1 |
| B: extraOccurrences | 2 − 1 = **1** |

The formula uses `− 1` because the first B is normal work. Only the repeats count as rework.

---

### 8. Start/end activities: "How do cases begin and end?"

**Formula:**
```
start count(X) = number of cases whose first activity is X
end count(X)   = number of cases whose last activity is X
percentage     = count / total cases × 100
```

| | Result |
|---|---|
| Starts | A: 4 cases (100%) |
| Ends | C: 4 cases (100%) |

**Use:** if some cases end at an unexpected activity, they are probably stuck or were abandoned.

---

### 9. Throughput: "How many cases finish per day?"

**Formula:**
```
completion day(case) = date (UTC) of the case's last event
throughput(day)      = number of cases whose completion day is that day
```

The optional `?endActivity=C` parameter counts only cases whose last activity is C. That way, unfinished cases are left out.

The practice log only has minutes, so assume cases 1 and 2 finished on Sep 1 and cases 3 and 4 on Sep 2. The result is **Sep 1: 2, Sep 2: 2**.

**Use:** you can see whether the process is keeping up or falling behind over time.

---

### Cheat sheet

| Metric | Unit it looks at | Core formula |
|---|---|---|
| Variant | whole path of a case | count identical paths |
| Case duration | one case | last time − first time |
| Duration stats | a list of durations | min, avg, median, P90, P95, max |
| Activity frequency | one activity | occurrences, and cases containing it |
| Transition | pair of neighbouring events | count + time between them |
| Bottleneck | transition | highest average, among transitions seen at least N times |
| Rework | activity within one case | appears more than once |
| Start/end | first and last event | count them |
| Throughput | day | cases completed that day |

# Phase 5 — Conformance Checking

## 15. The alignment DP, step by step

### What we are trying to do

We have the **expected** path and the **actual** path of one case:

```
expected: A  B  C
actual:   A  C
```

We want the **cheapest** way to line them up, using three kinds of move:

| Move | Meaning | Cost |
|---|---|---|
| `MATCH` | the same activity is in both, so pair them | 0 |
| `SKIPPED` | an activity is in expected but missing from actual | 1 |
| `EXTRA` | an activity is in actual but not expected at this point | 1 |

The cheapest line-up for the example is:

```
expected: A  B  C
actual:   A  -  C
          ✓  ↑  ✓
          MATCH  SKIPPED B  MATCH      → cost 1
```

The total cost is the number of deviations. The list of moves tells you **what** the deviations are.

### The whole request flow

```
POST /api/conformance  {"expected": ["A","B","C"]}
        ↓
ConformanceController.checkConformance()
        ↓  @Valid: an empty "expected" → 400
ConformanceService.checkConformance(expected)
        ↓
eventLogService.loadCaseTraces()        ← same Phase 4 pipeline: grouped + sorted cases
        ↓
computeConformance(traces, expected)
        ↓  for each case:
        │     checkCase(trace, expected)
        │        ├── align(actual, expected)    ← the DP: moves + cost
        │        ├── deviations = moves that are not MATCH
        │        └── fitness(cost, actual size, expected size)
        ↓
summary: fitting cases, fitting %, average fitness, deviation counts
        ↓
ConformanceResponse → JSON
```

### Why we need DP, and why greedy fails

Greedy means walking left to right and making the choice that looks best right now. It can pick the wrong move, because it can't see what comes later.

```
expected: A  B
actual:   B  A  B
```

First step: actual has `B`, expected has `A`. They differ, so either `B` is extra or `A` was skipped.

- **Choose "skip A":** then `B` matches `B`, and the remaining `A` and `B` in actual are both extra. **Cost 3.**
- **Choose "B is extra":** then `A` matches `A` and `B` matches `B`. **Cost 1.** ✅

Both options look the same at that moment, and only one is right. DP solves this by working out the best answer for **every** remaining-suffix pair first. Each decision then knows the true cost of what follows it.

### The table

`cost[i][j]` = the cheapest way to align **the rest of actual, from position i** with **the rest of expected, from position j**.

- `i` goes from 0 to m (m = actual length); `i == m` means "nothing left in actual".
- `j` goes from 0 to k (k = expected length); `j == k` means "nothing left in expected".
- The answer for the whole case is `cost[0][0]`: everything aligned with everything.

That's why the table is `new int[m + 1][k + 1]`. The extra row and column stand for "nothing left".

### The three rules (the recurrence)

**Edges (when one side has run out):**
```java
if (i == m)      cost[i][j] = k - j;   // actual finished → every remaining expected activity is SKIPPED
else if (j == k) cost[i][j] = m - i;   // expected finished → every remaining actual activity is EXTRA
```

**Every other cell.** Look at the next actual activity `actual[i]` and the next expected activity `expected[j]`. There are up to three options:

```java
int best = 1 + Math.min(
        cost[i + 1][j],        // EXTRA:   use up actual[i],   stay on expected[j]  → move DOWN
        cost[i][j + 1]);       // SKIPPED: use up expected[j], stay on actual[i]    → move RIGHT
if (actual[i] equals expected[j])
    best = Math.min(best, cost[i + 1][j + 1]);   // MATCH: use up both, free → move DIAGONALLY
cost[i][j] = best;
```

Each option takes the cost of **this** move plus the already-known best cost of **what's left** after it. That is the whole DP idea.

### Why the loops run backwards

```java
for (int i = m; i >= 0; i--)
    for (int j = k; j >= 0; j--)
```

Every cell depends on cells **below** it (`i + 1`), **to the right** (`j + 1`) or **diagonally below-right**. Filling from the bottom-right corner means those cells are always ready before we need them.

### Filling the table by hand: actual `[A, C]`, expected `[A, B, C]`

m = 2, k = 3, so the table is 3 × 4.

**Step 1: the edges.**
- Bottom row, `i = 2` (actual finished): `cost = 3 − j` gives 3, 2, 1, 0.
- Right column, `j = 3` (expected finished): `cost = 2 − i` gives 2, 1, 0.

```
            j=0 A   j=1 B   j=2 C   j=3 (end)
i=0 A         ?       ?       ?       2
i=1 C         ?       ?       ?       1
i=2 (end)     3       2       1       0
```

**Step 2: row i = 1 (`actual[1] = C`), right to left.**

| Cell | Compare | EXTRA = 1 + below | SKIPPED = 1 + right | MATCH = diagonal | Result |
|---|---|---|---|---|---|
| [1][2] | C vs C ✓ | 1 + 1 = 2 | 1 + 1 = 2 | cost[2][3] = **0** | **0** |
| [1][1] | C vs B ✗ | 1 + 2 = 3 | 1 + 0 = **1** | — | **1** |
| [1][0] | C vs A ✗ | 1 + 3 = 4 | 1 + 1 = **2** | — | **2** |

**Step 3: row i = 0 (`actual[0] = A`), right to left.**

| Cell | Compare | EXTRA = 1 + below | SKIPPED = 1 + right | MATCH = diagonal | Result |
|---|---|---|---|---|---|
| [0][2] | A vs C ✗ | 1 + 0 = **1** | 1 + 2 = 3 | — | **1** |
| [0][1] | A vs B ✗ | 1 + 1 = **2** | 1 + 1 = **2** | — | **2** |
| [0][0] | A vs A ✓ | 1 + 2 = 3 | 1 + 2 = 3 | cost[1][1] = **1** | **1** |

**The finished table:**

```
            j=0 A   j=1 B   j=2 C   j=3 (end)
i=0 A        [1]      2       1       2
i=1 C         2      [1]     [0]      1
i=2 (end)     3       2       1      [0]
```

`cost[0][0] = 1`, so the best alignment has exactly **1 deviation**. The cells in `[ ]` are the path the trace-back takes (next step).

### Trace-back: turning the numbers into moves

The table only tells us the **cost**. To find **which** moves give that cost, walk from `(0, 0)` to `(m, k)`. At each cell, take a move whose result matches the cell's value, which means the move stays on an optimal path:

```java
while (i < m || j < k) {
    if (both left && actual[i] equals expected[j] && cost[i][j] == cost[i+1][j+1])  → MATCH,   i++, j++
    else if (actual left && cost[i][j] == 1 + cost[i+1][j])                          → EXTRA,   i++
    else                                                                             → SKIPPED, j++
}
```

Walking the example:

| At | Compare | Check | Move | Go to |
|---|---|---|---|---|
| (0,0) | A vs A | equal, and cost[0][0] = 1 == cost[1][1] = 1 ✓ | **MATCH A** | (1,1) |
| (1,1) | C vs B | not equal. EXTRA? 1 == 1 + cost[2][1] = 3 ✗ | **SKIPPED B** | (1,2) |
| (1,2) | C vs C | equal, and 0 == cost[2][3] = 0 ✓ | **MATCH C** | (2,3) = end |

Result: `MATCH A, SKIPPED B, MATCH C`, with cost 1.

**Why the trace-back walks forwards:** the table is built "from position i to the end". Walking from (0,0) therefore produces the moves in the right order, with no need to reverse the list at the end.

**Ties:** when more than one move keeps the optimal cost, the order of the `if` checks decides: MATCH first, then EXTRA, then SKIPPED. That makes the output deterministic, so the tests can assert exact moves.

### Second example: wrong order, actual `[A, C, B]`

```
expected: A  B  -  C
actual:   A  C  B  -
          ✓  +  ✓  ↑
MATCH A, EXTRA C, MATCH B, SKIPPED C   → cost 2
```

"C came too early" appears as **EXTRA C** (at the wrong place) plus **SKIPPED C** (missing at the right place). Plain alignments have no "swapped" move, so an order problem always costs 2.

### Fitness

```
fitness = 1 − cost / (actual length + expected length)
```

The denominator is the **worst possible** cost: nothing matches, so every actual activity is EXTRA and every expected activity is SKIPPED. Fitness therefore always lies between 0 and 1.

| Case | Actual | Cost | Worst | Fitness |
|---|---|---|---|---|
| 1 | A B C | 0 | 6 | **1.0** |
| 3 | A B B C | 1 | 7 | 1 − 1/7 = **0.857** |
| 4 | A C | 1 | 5 | 1 − 1/5 = **0.8** |
| | A C B | 2 | 6 | 1 − 2/6 = **0.667** |
| | X (against A B) | 3 | 3 | **0.0** |

Why not just use `1 − cost / expected length`? A long trace with lots of extra activities could push the fitness below 0. Dividing by the worst case keeps it in the 0–1 range.

### The summary (`computeConformance`)

For the practice log, expected `[A, B, C]`:

| Field | How it's computed | Value |
|---|---|---|
| totalCases | `traces.size()` | 4 |
| fittingCases | cases where cost == 0 | 2 |
| fittingPercentage | `percentage(2, 4)` (reused from `AnalyticsService`) | 50.0 |
| averageFitness | mean of the fitness values (1 + 1 + 0.857 + 0.8) / 4 | 0.914 |
| deviations | how many cases have each (type, activity) deviation; a `HashSet` counts each case once | SKIPPED B: 1 case, EXTRA B: 1 case |
| cases | one result per case, **worst fitness first** | 4, 3, 1, 2 |

- The deviation counts reuse the Phase 4 trick: `AlignmentMove` is a record, so two `AlignmentMove(EXTRA, "B")` objects are `equal()` and can share a `HashMap` key.
- When deviation counts tie, SKIPPED sorts before EXTRA because enums compare in declaration order (`MATCH, SKIPPED, EXTRA`).

### Complexity

- **One case:** the table has (m + 1) × (k + 1) cells, each O(1), so the time is **O(m × k)** and the table uses O(m × k) memory.
- **All cases:** the sum of mᵢ × k over all cases is **O(n × k)**, where n is the total number of events. It's linear in the log size, because the expected path k is short.
- Loading still dominates, as in Phase 4 (§12). All n events are loaded into memory first.
- **Memory could be reduced:** computing only the cost needs just two rows, O(k). Computing the moves as well needs the full table, or a cleverer algorithm (Hirschberg's).

### Limits (items 58–59)

- **A single expected sequence can't express loops or optional steps.** ORDER-952's repeated `Inspected → Packed` rounds are counted as EXTRA, even if the business allows re-inspection.
- **It can't express parallel activities** either. "B and C in any order" would need a richer model; industrial tools use Petri nets or BPMN with token replay or A* alignments.
- **Every move costs 1.** Real tools let you weight moves, e.g. skipping "Payment" is worse than an extra "Email sent".

# Phase 7 — Frontend

## 16. The dashboard: how it's built and why

### Run it
```bash
./mvnw spring-boot:run                      # backend on 8080
cd frontend && npm install && npm run dev   # dashboard on http://localhost:5173
node frontend/scripts/generate-log.mjs > generated_events.csv   # 2,500 synthetic cases (GEN-…)
```
Import the CSV with the **Import CSV** button, or drag the file onto the page.

### Stack
| Piece | Choice | Why |
|---|---|---|
| App | React + Vite + Tailwind v4 (JavaScript) | linked views (clicking a variant changes the map, the replay and the side panel) need shared state |
| Map | React Flow + elkjs | React Flow does pan/zoom and lets nodes/edges be React components; elkjs does the layered layout |
| Replay | plain `<canvas>` + `requestAnimationFrame` | thousands of moving dots; SVG would create one DOM element per dot |
| Charts | hand-written SVG | exact control over the colors, and no chart-library theming |

ECharts was in the original plan but was dropped: plain SVG was simpler and matched the design tokens exactly.

### Data flow
```
useDashboardData()  ── Promise.all over 8 endpoints ──►  data
        │                                           (traces parsed to {activity, time ms})
        ├── KpiStrip          durations, rework, variants
        ├── MapSection        dfg + transitions + activities + rework → buildModel → elk layout
        │     ├── ProcessMap (React Flow)  edges styled by view
        │     ├── SidePanel                /bottlenecks (own request, re-fetched when top/minFrequency change)
        │     └── ReplayCanvas             traces → trips → one shared clock
        ├── VariantExplorer   variants
        ├── DurationHistogram durations
        ├── ThroughputCalendar /throughput?endActivity (own request)
        └── CaseTimeline      traces
```
- **Selection context** (`selection.jsx`) holds one thing, `{type, id}`, where type is `edge`, `activity`, `variant` or `case`. Every view reads it, so clicking a variant row spotlights its path on the map and filters the replay to its cases.

### DFG, not DAG
The graph has cycles: Inspected → Packed (rework) and Payment Attempt → Payment Attempt (a self-loop). A layered layout needs an acyclic graph, so ELK **temporarily reverses back-edges** while it places the nodes (`cycleBreaking: MODEL_ORDER`, using the process order we feed it), then routes them back upwards. The frontend calls an edge **rework** if it's a self-loop or points to an activity that comes earlier in the process order (`graph.js → isRework`).

### Process order
Activities are sorted by the average relative position at which they first appear in a case (`processOrder` in `graph.js`). This gives the order Order Created → Payment → Packed → … without hard-coding it, so it works for any imported log.

### Visual encoding: one visual property carries one piece of data
| Property | Data |
|---|---|
| edge width | how often the step happens (√count, so rare steps stay visible) |
| edge color, performance view | average wait, on a 5-step log scale from warm gray to rust |
| orange edge + rank label | the top N from `/bottlenecks` (D4: top N with a minimum frequency) |
| dashed teal | rework edges |
| node color stripe | activity identity (the same color in variants and the case timeline) |
| dots | cases, moving along the edge they are currently "waiting on" |
| bar next to a node | how many cases are waiting to leave that activity right now |

**Palette ("warehouse floor"):** warm paper and ink neutrals, one safety orange that only ever means "slow", and teal plus a dash for rework. That way, color is never the only signal.

### CORS and the Vite proxy
- The page is served from `localhost:5173` and the API lives on `localhost:8080`. A different port counts as a **different origin**, so the browser blocks the response unless the server sends `Access-Control-Allow-Origin`.
- Fix used: the **Vite dev proxy** (`vite.config.js`: `'/api' → 8080`). The browser only ever talks to 5173, and Vite forwards the request server-to-server, where CORS doesn't apply.
- The alternative is Spring CORS config (`@CrossOrigin` or a `WebMvcConfigurer`). In production you'd usually serve both from one origin, e.g. a reverse proxy or Spring serving the built files.

### Token replay: how it works
1. **Trips** (`replay/trips.js`): for every case, each pair of neighbouring events becomes one trip `{caseId, edge, start, end}`. This is the same neighbour walk as the backend's directly-follows code, but it keeps the timestamps. The trips are sorted by start time.
2. **Clock** (`replay/clock.js`): one mutable object, `now`. At 1× speed, 12 hours of log time pass per real second. It isn't React state, because updating state 60 times a second would re-render the whole app.
3. **Each animation frame** (`ReplayCanvas`):
   - move `now` forward
   - ask the **sweep** which trips are active: a pointer adds trips whose start has passed, and a filter drops the ones that have finished. This is the same "sort once, then read in order" idea as Phase 3.
   - for each active trip: `fraction = (now − start) / (end − start)`, then the point at that fraction along the edge's route (`geometry.js → polyline().pointAt`), then a small per-case sideways offset so a crowd shows up as a crowd
   - convert map coordinates to screen coordinates with React Flow's viewport transform, so the dots follow pan and zoom
4. **Idle skip:** if no case is in progress and the next trip starts more than 6 hours later, the clock jumps ahead, so nights and gaps in the log don't play out as empty animation.
5. **Counters:** "open" = distinct cases with an active trip. "done" = a binary search over sorted case end times.

**Why the queue forms:** Payment Received → Packed averages about 7.7 hours while the other steps take minutes, so at any moment about 40 of the ~60 open cases sit on that one edge. You can watch Little's law happen: number waiting ≈ arrival rate × wait time.

### Synthetic data (`frontend/scripts/generate-log.mjs`)
2,500 cases over 14 days, with a seeded random generator, so the file is the same every run. The planted patterns:

| Pattern | How it's planted | Where it shows |
|---|---|---|
| bottleneck | Payment Received → Packed ≈ 6h log-normal, ×1.8 on weekends | #1 slowest step, rust edge, queue in the replay |
| rework | 18% of cases fail inspection (35% again after that, up to 3 rounds) | teal dashed Inspected → Packed, ~22% rework KPI |
| payment retries | 22% of cases have 1–2 Payment Attempts | Payment Attempt self-loop |
| stalls | 4% stop at Order Created, 5% at Packed, 6% at Shipped | dotted edges into End from the wrong activities |
| weekday pattern | weekend arrivals are thinned to 35% | throughput calendar: ~50/day on weekdays vs ~31 at weekends |

Being able to say "I planted a bottleneck and the tool found it" is also a check that the analytics are correct.

The old hand-written test cases (ORDER-…, dated Sep 17–19) are still in the database. That's why the log span shows 47 days and the calendar has an empty gap. Delete them to get a tidy demo:
```sql
delete from events where process_case_id in (select id from process_cases where case_id like 'ORDER-%');
delete from process_cases where case_id like 'ORDER-%';
```

### Duplicated logic: a trade-off to be ready to defend
The replay counters and the "rework case" coloring are recomputed in JavaScript from `/traces`. That duplicates Java logic, which can drift out of sync (§12, Q4). The rule used here: **the static views show backend numbers**, and the frontend only recomputes what the animation needs, which is things that depend on "now".

### What the dashboard exposes about the backend (leads into Phase 6)
- **Eight full loads per page:** opening the dashboard loads the entire event log about 8 times (D7, one endpoint per metric). This is the case for a summary endpoint or `@Cacheable` with eviction on import (§12, Q5).
- **Invalid parameters:** the bottleneck inputs are clamped in the frontend, because `top=0` or a negative value would get a 500 from the backend. Phase 6 should return a 400 instead.
- **`/traces` returns every event.** Fine at 16k events; at millions, the replay would have to use a sample of cases.
- **Import is slow:** 16k rows take about 20 s, because of one `findByCaseId` per case and one INSERT per event (`IDENTITY` ids prevent batching).
