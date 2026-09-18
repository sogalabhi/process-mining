package com.abhi.processmining.dto.analytics;

import java.util.List;

public record CaseDurationsResponse(
        int caseCount,
        DurationStats stats,
        List<CaseDuration> cases
) {

    public record CaseDuration(String caseId, long durationSeconds) {}
}
