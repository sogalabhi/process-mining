package com.abhi.processmining.dto.analytics;

public record TransitionStatsResponse(
        String from,
        String to,
        int frequency,
        DurationStats duration
) {}
