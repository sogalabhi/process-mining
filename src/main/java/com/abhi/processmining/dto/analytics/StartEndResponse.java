package com.abhi.processmining.dto.analytics;

import java.util.List;

public record StartEndResponse(
        int caseCount,
        List<ActivityShare> startActivities,
        List<ActivityShare> endActivities
) {

    public record ActivityShare(String activity, int count, double percentage) {}
}
