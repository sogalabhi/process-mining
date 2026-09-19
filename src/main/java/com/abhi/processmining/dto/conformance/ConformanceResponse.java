package com.abhi.processmining.dto.conformance;

import com.abhi.processmining.model.MoveType;

import java.util.List;

public record ConformanceResponse(
        List<String> expected,
        int totalCases,
        int fittingCases,
        double fittingPercentage,
        double averageFitness,
        List<DeviationCount> deviations,
        List<CaseConformance> cases
) {

    public record DeviationCount(MoveType type, String activity, int cases, double percentage) {}
}
