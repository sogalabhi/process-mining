package com.abhi.processmining.service;

import com.abhi.processmining.model.CaseTrace;
import com.abhi.processmining.model.Event;
import com.abhi.processmining.model.ProcessCase;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventLogServiceTest {

    private final EventLogService service = new EventLogService(null);

    @Test
    void groupsByCaseAndSortsByTimestamp() {
        ProcessCase order = new ProcessCase("ORDER-900");
        order.addEvent(new Event("Shipped", Instant.parse("2026-09-18T12:00:00Z")));
        order.addEvent(new Event("Order Created", Instant.parse("2026-09-18T10:00:00Z")));
        order.addEvent(new Event("Packed", Instant.parse("2026-09-18T11:00:00Z")));

        List<CaseTrace> traces = service.toCaseTraces(order.getEvents());

        assertThat(traces).hasSize(1);
        assertThat(traces.getFirst().caseId()).isEqualTo("ORDER-900");
        assertThat(traces.getFirst().activities())
                .containsExactly("Order Created", "Packed", "Shipped");
    }
}
