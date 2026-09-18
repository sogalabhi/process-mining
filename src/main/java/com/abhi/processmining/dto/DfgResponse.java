package com.abhi.processmining.dto;

import java.util.List;

public record DfgResponse(
        List<String> activities,
        List<TransitionResponse> transitions
) {}