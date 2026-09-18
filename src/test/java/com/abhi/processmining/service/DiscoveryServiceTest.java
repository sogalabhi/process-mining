package com.abhi.processmining.service;

import com.abhi.processmining.dto.DfgResponse;
import com.abhi.processmining.dto.TransitionResponse;
import com.abhi.processmining.model.Event;
import com.abhi.processmining.model.Transition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class DiscoveryServiceTest {

    private final DiscoveryService service = new DiscoveryService(null);

    @Test
    void singleActivityTraceHasNoPairs() {
        var pairs = service.directlyFollows(List.of("Order Created"));

        assertThat(pairs).isEmpty();
    }

    @Test
    void sortsEventsByTimestamp() {
        Map<String, List<Event>> eventsByCase = new HashMap<>();
        eventsByCase.put("ORDER-900", new ArrayList<>(List.of(
                new Event("Shipped", Instant.parse("2026-09-18T12:00:00Z")),
                new Event("Order Created", Instant.parse("2026-09-18T10:00:00Z")),
                new Event("Packed", Instant.parse("2026-09-18T11:00:00Z"))
        )));

        service.sortEventsByTimestamp(eventsByCase);

        assertThat(service.buildTraces(eventsByCase).get("ORDER-900"))
                .containsExactly("Order Created", "Packed", "Shipped");
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
        Map<String, List<String>> traces = Map.of(
                "1", List.of("A", "B", "C", "D"),
                "2", List.of("A", "B", "C", "D"),
                "3", List.of("A", "B", "D")
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
        Map<String, List<String>> traces = Map.of(
                "1", List.of("Packed", "Inspected", "Packed", "Inspected", "Packed")
        );

        assertThat(service.countTransitions(traces)).containsOnly(
                entry(new Transition("Packed", "Inspected"), 2),
                entry(new Transition("Inspected", "Packed"), 2)
        );
    }

    @Test
    void selfLoopIsCountedLikeAnyTransition() {
        Map<String, List<String>> traces = Map.of(
                "1", List.of("Payment Attempt", "Payment Attempt", "Payment Attempt")
        );

        assertThat(service.countTransitions(traces)).containsOnly(
                entry(new Transition("Payment Attempt", "Payment Attempt"), 2)
        );
    }

    @Test
    void countsStartAndEndActivities() {
        Map<String, List<String>> traces = Map.of(
                "1", List.of("A", "B", "C"),
                "2", List.of("A", "C"),
                "3", List.of("A")
        );

        assertThat(service.countStartActivities(traces)).containsOnly(entry("A", 3));
        assertThat(service.countEndActivities(traces)).containsOnly(entry("C", 2), entry("A", 1));
    }

    @Test
    void dfgIncludesLoneActivitiesAndSortsTransitionsByCount() {
        Map<String, List<String>> traces = Map.of(
                "1", List.of("A", "B"),
                "2", List.of("A", "B"),
                "3", List.of("B", "C"),
                "4", List.of("X")
        );

        DfgResponse dfg = service.buildDfg(traces);

        assertThat(dfg.activities()).containsExactly("A", "B", "C", "X");
        assertThat(dfg.transitions()).containsExactly(
                new TransitionResponse("A", "B", 2),
                new TransitionResponse("B", "C", 1)
        );
    }
}
