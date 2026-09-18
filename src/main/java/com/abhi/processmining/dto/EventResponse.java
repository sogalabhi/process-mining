package com.abhi.processmining.dto;

import java.time.Instant;

public record EventResponse(
        String caseId,
        String activity,
        Instant timestamp
) {}