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

## 12. Item 46: questions to answer (still open)

Write your own answers, then review them.
1. What's the time complexity of each metric in terms of n events and c cases? Which step dominates?
2. What's the memory cost of loading every event into `List<CaseTrace>`?
3. At 50 million events, which endpoints break first, and why?
4. Which metrics could be pushed into SQL (`GROUP BY`, window functions like `LAG`) instead of Java? What would that cost in readability and testability?
5. When would you precompute or cache metrics instead of computing them per request?
