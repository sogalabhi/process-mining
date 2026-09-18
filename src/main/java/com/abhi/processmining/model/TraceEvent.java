package com.abhi.processmining.model;

import java.time.Instant;

public record TraceEvent(String activity, Instant timestamp) {}
