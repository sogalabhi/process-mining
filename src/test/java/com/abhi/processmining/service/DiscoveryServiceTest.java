package com.abhi.processmining.service;

import com.abhi.processmining.dto.DfgResponse;
import com.abhi.processmining.dto.TransitionResponse;
import com.abhi.processmining.model.CaseTrace;
import com.abhi.processmining.model.TraceEvent;
import com.abhi.processmining.model.Transition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class DiscoveryServiceTest {

    private final DiscoveryService service = new DiscoveryService(null, null);

    @Test
    void singleActivityTraceHasNoPairs() {
        var pairs = service.directlyFollows(List.of("Order Created"));

        assertThat(pairs).isEmpty();
    }

    @Test
    void traceProducesAdjacentPairsInOrder() {
        List<Transition> pairs = service.directlyFollows(List.of("A", "B", "C"));

        assertThat(pairs).containsExactly(
                new Transition("A", "B"),
                new Transition("B", "C")
        );
    }

    @Test
    void countsTransitionsAcrossCases() {
        List<CaseTrace> traces = List.of(
                trace("1", "A", "B", "C", "D"),
                trace("2", "A", "B", "C", "D"),
                trace("3", "A", "B", "D")
        );

        Map<Transition, Integer> counts = service.countTransitions(traces);

        assertThat(counts).containsOnly(
                entry(new Transition("A", "B"), 3),
                entry(new Transition("B", "C"), 2),
                entry(new Transition("C", "D"), 2),
                entry(new Transition("B", "D"), 1)
        );
    }

    @Test
    void loopCountsBothDirectionsPerOccurrence() {
        List<CaseTrace> traces = List.of(
                trace("1", "Packed", "Inspected", "Packed", "Inspected", "Packed")
        );

        assertThat(service.countTransitions(traces)).containsOnly(
                entry(new Transition("Packed", "Inspected"), 2),
                entry(new Transition("Inspected", "Packed"), 2)
        );
    }

    @Test
    void selfLoopIsCountedLikeAnyTransition() {
        List<CaseTrace> traces = List.of(
                trace("1", "Payment Attempt", "Payment Attempt", "Payment Attempt")
        );

        assertThat(service.countTransitions(traces)).containsOnly(
                entry(new Transition("Payment Attempt", "Payment Attempt"), 2)
        );
    }

    @Test
    void countsStartAndEndActivities() {
        List<CaseTrace> traces = List.of(
                trace("1", "A", "B", "C"),
                trace("2", "A", "C"),
                trace("3", "A")
        );

        assertThat(service.countStartActivities(traces)).containsOnly(entry("A", 3));
        assertThat(service.countEndActivities(traces)).containsOnly(entry("C", 2), entry("A", 1));
    }

    @Test
    void dfgIncludesLoneActivitiesAndSortsTransitionsByCount() {
        List<CaseTrace> traces = List.of(
                trace("1", "A", "B"),
                trace("2", "A", "B"),
                trace("3", "B", "C"),
                trace("4", "X")
        );

        DfgResponse dfg = service.buildDfg(traces);

        assertThat(dfg.activities()).containsExactly("A", "B", "C", "X");
        assertThat(dfg.transitions()).containsExactly(
                new TransitionResponse("A", "B", 2),
                new TransitionResponse("B", "C", 1)
        );
    }

    private static CaseTrace trace(String caseId, String... activities) {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        List<TraceEvent> events = new ArrayList<>();

        for (int i = 0; i < activities.length; i++) {
            events.add(new TraceEvent(activities[i], start.plusSeconds(i)));
        }

        return new CaseTrace(caseId, events);
    }
}
