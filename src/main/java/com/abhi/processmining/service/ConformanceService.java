package com.abhi.processmining.service;

import com.abhi.processmining.dto.conformance.CaseConformance;
import com.abhi.processmining.dto.conformance.ConformanceResponse;
import com.abhi.processmining.dto.conformance.ConformanceResponse.DeviationCount;
import com.abhi.processmining.model.Alignment;
import com.abhi.processmining.model.AlignmentMove;
import com.abhi.processmining.model.CaseTrace;
import com.abhi.processmining.model.MoveType;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ConformanceService {

    private final EventLogService eventLogService;

    public ConformanceService(EventLogService eventLogService) {
        this.eventLogService = eventLogService;
    }

    public ConformanceResponse checkConformance(List<String> expected) {
        return computeConformance(eventLogService.loadCaseTraces(), expected);
    }

    public ConformanceResponse computeConformance(List<CaseTrace> traces, List<String> expected) {
        List<CaseConformance> cases = traces.stream()
                .map(trace -> checkCase(trace, expected))
                .sorted(Comparator.comparingDouble(CaseConformance::fitness)
                        .thenComparing(CaseConformance::caseId))
                .toList();

        int fittingCases = (int) cases.stream().filter(CaseConformance::fits).count();
        double averageFitness = cases.stream().mapToDouble(CaseConformance::fitness).average().orElse(0);

        return new ConformanceResponse(
                expected,
                traces.size(),
                fittingCases,
                AnalyticsService.percentage(fittingCases, traces.size()),
                round(averageFitness),
                deviationCounts(cases, traces.size()),
                cases
        );
    }

    CaseConformance checkCase(CaseTrace trace, List<String> expected) {
        List<String> actual = trace.activities();
        Alignment alignment = align(actual, expected);

        List<AlignmentMove> deviations = alignment.moves().stream()
                .filter(move -> move.type() != MoveType.MATCH)
                .toList();

        return new CaseConformance(
                trace.caseId(),
                actual,
                alignment.cost() == 0,
                fitness(alignment.cost(), actual.size(), expected.size()),
                alignment.cost(),
                deviations
        );
    }

    static Alignment align(List<String> actual, List<String> expected) {
        int m = actual.size();
        int k = expected.size();

        int[][] cost = new int[m + 1][k + 1];

        for (int i = m; i >= 0; i--) {
            for (int j = k; j >= 0; j--) {
                if (i == m) {
                    cost[i][j] = k - j;
                } else if (j == k) {
                    cost[i][j] = m - i;
                } else {
                    int best = 1 + Math.min(cost[i + 1][j], cost[i][j + 1]);
                    if (actual.get(i).equals(expected.get(j))) {
                        best = Math.min(best, cost[i + 1][j + 1]);
                    }
                    cost[i][j] = best;
                }
            }
        }

        List<AlignmentMove> moves = new ArrayList<>();
        int i = 0;
        int j = 0;

        while (i < m || j < k) {
            if (i < m && j < k
                    && actual.get(i).equals(expected.get(j))
                    && cost[i][j] == cost[i + 1][j + 1]) {
                moves.add(new AlignmentMove(MoveType.MATCH, actual.get(i)));
                i++;
                j++;
            } else if (i < m && cost[i][j] == 1 + cost[i + 1][j]) {
                moves.add(new AlignmentMove(MoveType.EXTRA, actual.get(i)));
                i++;
            } else {
                moves.add(new AlignmentMove(MoveType.SKIPPED, expected.get(j)));
                j++;
            }
        }

        return new Alignment(moves, cost[0][0]);
    }

    static double fitness(int cost, int actualSize, int expectedSize) {
        int worst = actualSize + expectedSize;
        if (worst == 0) {
            return 1;
        }
        return round(1 - (double) cost / worst);
    }

    private static List<DeviationCount> deviationCounts(List<CaseConformance> cases, int totalCases) {
        Map<AlignmentMove, Integer> counts = new HashMap<>();

        for (CaseConformance result : cases) {
            for (AlignmentMove deviation : new HashSet<>(result.deviations())) {
                counts.merge(deviation, 1, Integer::sum);
            }
        }

        return counts.entrySet().stream()
                .map(entry -> new DeviationCount(
                        entry.getKey().type(),
                        entry.getKey().activity(),
                        entry.getValue(),
                        AnalyticsService.percentage(entry.getValue(), totalCases)
                ))
                .sorted(Comparator.comparingInt(DeviationCount::cases).reversed()
                        .thenComparing(DeviationCount::type)
                        .thenComparing(DeviationCount::activity))
                .toList();
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}
