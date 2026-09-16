package com.abhi.processmining.dto;

import java.time.Instant;

public record EventCsvRow(
        String caseId,
        String activity,
        Instant timestamp
) {
}