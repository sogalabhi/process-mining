package com.abhi.processmining.dto.analytics;

import java.util.List;

public record VariantResponse(
        List<String> activities,
        int frequency,
        double percentage
) {}
