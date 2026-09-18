package com.abhi.processmining.dto;

import java.util.List;
import java.util.Map;

public record DfgResponse(
        List<String> activities,
        List<TransitionResponse> transitions,
        Map<String, Integer> startActivities,
        Map<String, Integer> endActivities
) {}