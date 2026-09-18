package com.abhi.processmining.dto.analytics;

public record ActivityFrequencyResponse(
        String activity,
        int occurrences,
        int cases,
        double casePercentage
) {}
