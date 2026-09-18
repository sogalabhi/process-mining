package com.abhi.processmining.dto.analytics;

import java.util.List;

public record ReworkResponse(
        int totalCases,
        int casesWithRework,
        double reworkPercentage,
        List<ActivityRework> activities
) {

    public record ActivityRework(String activity, int casesWithRepeat, int extraOccurrences) {}
}
