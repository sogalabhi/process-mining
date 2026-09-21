package com.abhi.processmining.dto;

import com.abhi.processmining.model.AlignmentStep;

import java.util.List;

public record ConformanceResult(
        String caseId,
        int editCost,
        double fitness,
        List<AlignmentStep> alignment
) {}
