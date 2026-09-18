package com.abhi.processmining.dto.analytics;

public record DurationStats(
        long minSeconds,
        long avgSeconds,
        long medianSeconds,
        long p90Seconds,
        long p95Seconds,
        long maxSeconds
) {}
