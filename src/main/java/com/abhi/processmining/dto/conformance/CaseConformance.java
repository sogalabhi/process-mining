package com.abhi.processmining.dto.conformance;

import com.abhi.processmining.model.AlignmentMove;

import java.util.List;

public record CaseConformance(
        String caseId,
        List<String> activities,
        boolean fits,
        double fitness,
        int cost,
        List<AlignmentMove> deviations
) {}
