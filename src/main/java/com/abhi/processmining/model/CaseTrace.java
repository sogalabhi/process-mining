package com.abhi.processmining.model;

import java.time.Duration;
import java.util.List;

public record CaseTrace(String caseId, List<TraceEvent> events) {

    public CaseTrace {
        events = List.copyOf(events);
    }

    public List<String> activities() {
        return events.stream().map(TraceEvent::activity).toList();
    }

    public TraceEvent first() {
        return events.getFirst();
    }

    public TraceEvent last() {
        return events.getLast();
    }

    public Duration duration() {
        return Duration.between(first().timestamp(), last().timestamp());
    }
}
