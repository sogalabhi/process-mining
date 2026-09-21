package com.abhi.processmining.dto;

public record ConformanceSummary(
        int totalCases,
        int conformingCases,
        int deviatingCases,
        double averageFitness,
        int skippedActivities,
        int extraActivities,
        int substitutions
) {}