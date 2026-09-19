package com.abhi.processmining.dto.conformance;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ConformanceRequest(
        @NotEmpty List<String> expected
) {}
