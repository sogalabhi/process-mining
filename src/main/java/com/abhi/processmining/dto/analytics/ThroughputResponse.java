package com.abhi.processmining.dto.analytics;

import java.time.LocalDate;
import java.util.List;

public record ThroughputResponse(
        String endActivity,
        int totalCases,
        int completedCases,
        List<DailyCount> perDay
) {

    public record DailyCount(LocalDate date, int completed) {}
}
