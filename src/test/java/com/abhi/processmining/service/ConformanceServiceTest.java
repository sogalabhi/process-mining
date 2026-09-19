package com.abhi.processmining.service;

import com.abhi.processmining.dto.conformance.CaseConformance;
import com.abhi.processmining.dto.conformance.ConformanceResponse;
import com.abhi.processmining.dto.conformance.ConformanceResponse.DeviationCount;
import com.abhi.processmining.model.Alignment;
import com.abhi.processmining.model.AlignmentMove;
import com.abhi.processmining.model.CaseTrace;
import com.abhi.processmining.model.TraceEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.abhi.processmining.model.MoveType.*;
import static org.assertj.core.api.Assertions.assertThat;

class ConformanceServiceTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
    private static final List<String> ABC = List.of("A", "B", "C");

    private final ConformanceService service = new ConformanceService(null);

    @Test
    void perfectTraceIsAllMatchesWithZeroCost() {
        Alignment alignment = ConformanceService.align(List.of("A", "B", "C"), ABC);

        assertThat(alignment.cost()).isZero();
        assertThat(alignment.moves()).containsExactly(
                new AlignmentMove(MATCH, "A"),
                new AlignmentMove(MATCH, "B"),
                new AlignmentMove(MATCH, "C")
        );
    }

    @Test
    void missingActivityIsSkipped() {
        Alignment alignment = ConformanceService.align(List.of("A", "C"), ABC);

        assertThat(alignment.cost()).isEqualTo(1);
        assertThat(alignment.moves()).containsExactly(
                new AlignmentMove(MATCH, "A"),
                new AlignmentMove(SKIPPED, "B"),
                new AlignmentMove(MATCH, "C")
        );
    }

    @Test
    void repeatedActivityIsExtra() {
        Alignment alignment = ConformanceService.align(List.of("A", "B", "B", "C"), ABC);

        assertThat(alignment.cost()).isEqualTo(1);
        assertThat(alignment.moves()).containsExactly(
                new AlignmentMove(MATCH, "A"),
                new AlignmentMove(MATCH, "B"),
                new AlignmentMove(EXTRA, "B"),
                new AlignmentMove(MATCH, "C")
        );
    }

    @Test
    void wrongOrderCostsOneExtraAndOneSkip() {
        Alignment alignment = ConformanceService.align(List.of("A", "C", "B"), ABC);

        assertThat(alignment.cost()).isEqualTo(2);
        assertThat(alignment.moves()).containsExactly(
                new AlignmentMove(MATCH, "A"),
                new AlignmentMove(EXTRA, "C"),
                new AlignmentMove(MATCH, "B"),
                new AlignmentMove(SKIPPED, "C")
        );
    }

    @Test
    void unrelatedTraceIsAllExtraAndSkipped() {
        Alignment alignment = ConformanceService.align(List.of("X"), List.of("A", "B"));

        assertThat(alignment.cost()).isEqualTo(3);
        assertThat(alignment.moves()).containsExactly(
                new AlignmentMove(EXTRA, "X"),
                new AlignmentMove(SKIPPED, "A"),
                new AlignmentMove(SKIPPED, "B")
        );
    }

    @Test
    void fitnessIsOneMinusCostOverWorstCase() {
        assertThat(ConformanceService.fitness(0, 3, 3)).isEqualTo(1.0);
        assertThat(ConformanceService.fitness(1, 2, 3)).isEqualTo(0.8);
        assertThat(ConformanceService.fitness(1, 4, 3)).isEqualTo(0.857);
        assertThat(ConformanceService.fitness(3, 1, 2)).isEqualTo(0.0);
    }

    @Test
    void summaryOverPracticeLog() {
        List<CaseTrace> traces = List.of(
                trace("1", "A", "B", "C"),
                trace("2", "A", "B", "C"),
                trace("3", "A", "B", "B", "C"),
                trace("4", "A", "C")
        );

        ConformanceResponse result = service.computeConformance(traces, ABC);

        assertThat(result.totalCases()).isEqualTo(4);
        assertThat(result.fittingCases()).isEqualTo(2);
        assertThat(result.fittingPercentage()).isEqualTo(50.0);
        assertThat(result.averageFitness()).isEqualTo(0.914);

        assertThat(result.cases()).extracting(CaseConformance::caseId).containsExactly("4", "3", "1", "2");
        assertThat(result.cases()).extracting(CaseConformance::fitness).containsExactly(0.8, 0.857, 1.0, 1.0);

        assertThat(result.deviations()).containsExactly(
                new DeviationCount(SKIPPED, "B", 1, 25.0),
                new DeviationCount(EXTRA, "B", 1, 25.0)
        );
    }

    @Test
    void deviationRepeatedInOneCaseCountsThatCaseOnce() {
        List<CaseTrace> traces = List.of(trace("1", "A", "B", "B", "B", "C"));

        ConformanceResponse result = service.computeConformance(traces, ABC);

        assertThat(result.cases().getFirst().deviations()).containsExactly(
                new AlignmentMove(EXTRA, "B"),
                new AlignmentMove(EXTRA, "B")
        );
        assertThat(result.deviations()).containsExactly(new DeviationCount(EXTRA, "B", 1, 100.0));
    }

    private static CaseTrace trace(String caseId, String... activities) {
        List<TraceEvent> events = new ArrayList<>();
        for (int i = 0; i < activities.length; i++) {
            events.add(new TraceEvent(activities[i], BASE.plusSeconds(60L * i)));
        }
        return new CaseTrace(caseId, events);
    }
}
