package com.abhi.processmining.service;

import com.abhi.processmining.dto.analytics.*;
import com.abhi.processmining.dto.analytics.CaseDurationsResponse.CaseDuration;
import com.abhi.processmining.dto.analytics.ReworkResponse.ActivityRework;
import com.abhi.processmining.dto.analytics.StartEndResponse.ActivityShare;
import com.abhi.processmining.dto.analytics.ThroughputResponse.DailyCount;
import com.abhi.processmining.model.CaseTrace;
import com.abhi.processmining.model.TraceEvent;
import com.abhi.processmining.model.Transition;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class AnalyticsService {

    private final EventLogService eventLogService;

    public AnalyticsService(EventLogService eventLogService) {
        this.eventLogService = eventLogService;
    }

    // Entry points: load the traces once, then apply one formula.

    public List<VariantResponse> getVariants() {
        return computeVariants(eventLogService.loadCaseTraces());
    }

    public CaseDurationsResponse getCaseDurations() {
        return computeCaseDurations(eventLogService.loadCaseTraces());
    }

    public List<ActivityFrequencyResponse> getActivityFrequencies() {
        return computeActivityFrequencies(eventLogService.loadCaseTraces());
    }

    public List<TransitionStatsResponse> getTransitionStats() {
        return computeTransitionStats(eventLogService.loadCaseTraces());
    }

    public List<TransitionStatsResponse> getBottlenecks(int top, int minFrequency) {
        return computeBottlenecks(eventLogService.loadCaseTraces(), top, minFrequency);
    }

    public ReworkResponse getRework() {
        return computeRework(eventLogService.loadCaseTraces());
    }

    public StartEndResponse getStartEnd() {
        return computeStartEnd(eventLogService.loadCaseTraces());
    }

    public ThroughputResponse getThroughput(String endActivity) {
        return computeThroughput(eventLogService.loadCaseTraces(), endActivity);
    }

    // 32–33: identical activity sequences form one variant.

    public List<VariantResponse> computeVariants(List<CaseTrace> traces) {
        Map<List<String>, Integer> counts = new HashMap<>();

        for (CaseTrace trace : traces) {
            counts.merge(trace.activities(), 1, Integer::sum);
        }

        return counts.entrySet().stream()
                .map(entry -> new VariantResponse(
                        entry.getKey(),
                        entry.getValue(),
                        percentage(entry.getValue(), traces.size())
                ))
                .sorted(Comparator.comparingInt(VariantResponse::frequency).reversed()
                        .thenComparing(variant -> String.join(" → ", variant.activities())))
                .toList();
    }

    // 34–35: case duration = last timestamp − first timestamp.

    public CaseDurationsResponse computeCaseDurations(List<CaseTrace> traces) {
        List<CaseDuration> cases = traces.stream()
                .map(trace -> new CaseDuration(trace.caseId(), trace.duration().toSeconds()))
                .sorted(Comparator.comparingLong(CaseDuration::durationSeconds).reversed()
                        .thenComparing(CaseDuration::caseId))
                .toList();

        List<Long> seconds = cases.stream()
                .map(CaseDuration::durationSeconds)
                .toList();

        return new CaseDurationsResponse(traces.size(), durationStats(seconds), cases);
    }

    // 36: occurrences count every appearance; cases count each case once.

    public List<ActivityFrequencyResponse> computeActivityFrequencies(List<CaseTrace> traces) {
        Map<String, Integer> occurrences = new HashMap<>();
        Map<String, Integer> cases = new HashMap<>();

        for (CaseTrace trace : traces) {
            for (String activity : trace.activities()) {
                occurrences.merge(activity, 1, Integer::sum);
            }
            for (String activity : new HashSet<>(trace.activities())) {
                cases.merge(activity, 1, Integer::sum);
            }
        }

        return occurrences.entrySet().stream()
                .map(entry -> new ActivityFrequencyResponse(
                        entry.getKey(),
                        entry.getValue(),
                        cases.get(entry.getKey()),
                        percentage(cases.get(entry.getKey()), traces.size())
                ))
                .sorted(Comparator.comparingInt(ActivityFrequencyResponse::occurrences).reversed()
                        .thenComparing(ActivityFrequencyResponse::activity))
                .toList();
    }

    // 37: time between each pair of consecutive events, grouped by transition.

    public Map<Transition, List<Long>> transitionDurations(List<CaseTrace> traces) {
        Map<Transition, List<Long>> durations = new HashMap<>();

        for (CaseTrace trace : traces) {
            List<TraceEvent> events = trace.events();

            for (int i = 0; i < events.size() - 1; i++) {
                TraceEvent from = events.get(i);
                TraceEvent to = events.get(i + 1);
                long seconds = Duration.between(from.timestamp(), to.timestamp()).toSeconds();

                durations.computeIfAbsent(new Transition(from.activity(), to.activity()), key -> new ArrayList<>())
                        .add(seconds);
            }
        }

        return durations;
    }

    // 38: frequency + duration stats per transition.

    public List<TransitionStatsResponse> computeTransitionStats(List<CaseTrace> traces) {
        return transitionDurations(traces).entrySet().stream()
                .map(entry -> new TransitionStatsResponse(
                        entry.getKey().from(),
                        entry.getKey().to(),
                        entry.getValue().size(),
                        durationStats(entry.getValue())
                ))
                .sorted(Comparator.comparingInt(TransitionStatsResponse::frequency).reversed()
                        .thenComparing(TransitionStatsResponse::from)
                        .thenComparing(TransitionStatsResponse::to))
                .toList();
    }

    // 39: slowest transitions by average, ignoring rare ones (decision D4).

    public List<TransitionStatsResponse> computeBottlenecks(List<CaseTrace> traces, int top, int minFrequency) {
        return computeTransitionStats(traces).stream()
                .filter(transition -> transition.frequency() >= minFrequency)
                .sorted(Comparator.comparingLong((TransitionStatsResponse transition) -> transition.duration().avgSeconds())
                        .reversed()
                        .thenComparing(TransitionStatsResponse::from)
                        .thenComparing(TransitionStatsResponse::to))
                .limit(top)
                .toList();
    }

    // 40: rework = the same activity more than once in one case (decision D5).

    public ReworkResponse computeRework(List<CaseTrace> traces) {
        int casesWithRework = 0;
        Map<String, Integer> casesWithRepeat = new HashMap<>();
        Map<String, Integer> extraOccurrences = new HashMap<>();

        for (CaseTrace trace : traces) {
            Map<String, Integer> countsInCase = new HashMap<>();
            for (String activity : trace.activities()) {
                countsInCase.merge(activity, 1, Integer::sum);
            }

            boolean hasRework = false;
            for (Map.Entry<String, Integer> entry : countsInCase.entrySet()) {
                if (entry.getValue() > 1) {
                    hasRework = true;
                    casesWithRepeat.merge(entry.getKey(), 1, Integer::sum);
                    extraOccurrences.merge(entry.getKey(), entry.getValue() - 1, Integer::sum);
                }
            }

            if (hasRework) {
                casesWithRework++;
            }
        }

        List<ActivityRework> activities = casesWithRepeat.entrySet().stream()
                .map(entry -> new ActivityRework(
                        entry.getKey(),
                        entry.getValue(),
                        extraOccurrences.get(entry.getKey())
                ))
                .sorted(Comparator.comparingInt(ActivityRework::casesWithRepeat).reversed()
                        .thenComparing(ActivityRework::activity))
                .toList();

        return new ReworkResponse(
                traces.size(),
                casesWithRework,
                percentage(casesWithRework, traces.size()),
                activities
        );
    }

    // 41: how cases start and end, as a share of all cases.

    public StartEndResponse computeStartEnd(List<CaseTrace> traces) {
        Map<String, Integer> starts = new HashMap<>();
        Map<String, Integer> ends = new HashMap<>();

        for (CaseTrace trace : traces) {
            starts.merge(trace.first().activity(), 1, Integer::sum);
            ends.merge(trace.last().activity(), 1, Integer::sum);
        }

        return new StartEndResponse(
                traces.size(),
                shares(starts, traces.size()),
                shares(ends, traces.size())
        );
    }

    // 42: completed cases per UTC day (decision D6).
    // endActivity == null means every case counts, completed at its last event.

    public ThroughputResponse computeThroughput(List<CaseTrace> traces, String endActivity) {
        Map<LocalDate, Integer> perDay = new TreeMap<>();
        int completed = 0;

        for (CaseTrace trace : traces) {
            TraceEvent last = trace.last();

            if (endActivity != null && !endActivity.equals(last.activity())) {
                continue;
            }

            perDay.merge(LocalDate.ofInstant(last.timestamp(), ZoneOffset.UTC), 1, Integer::sum);
            completed++;
        }

        List<DailyCount> days = perDay.entrySet().stream()
                .map(entry -> new DailyCount(entry.getKey(), entry.getValue()))
                .toList();

        return new ThroughputResponse(endActivity, traces.size(), completed, days);
    }

    // Shared helpers.

    // Returns null for an empty list: there are no stats to report.
    static DurationStats durationStats(List<Long> seconds) {
        if (seconds.isEmpty()) {
            return null;
        }

        List<Long> sorted = seconds.stream().sorted().toList();

        long sum = 0;
        for (long value : sorted) {
            sum += value;
        }

        return new DurationStats(
                sorted.getFirst(),
                Math.round((double) sum / sorted.size()),
                percentile(sorted, 50),
                percentile(sorted, 90),
                percentile(sorted, 95),
                sorted.getLast()
        );
    }

    // Nearest-rank (decision D2): always returns a value that actually occurred.
    static long percentile(List<Long> sorted, int p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(rank, 1) - 1);
    }

    // Rounded to 2 decimals, e.g. 3 of 11 → 27.27.
    static double percentage(int part, int total) {
        if (total == 0) {
            return 0;
        }
        return Math.round(part * 10000.0 / total) / 100.0;
    }

    private static List<ActivityShare> shares(Map<String, Integer> counts, int total) {
        return counts.entrySet().stream()
                .map(entry -> new ActivityShare(entry.getKey(), entry.getValue(), percentage(entry.getValue(), total)))
                .sorted(Comparator.comparingInt(ActivityShare::count).reversed()
                        .thenComparing(ActivityShare::activity))
                .toList();
    }
}
