package com.abhi.processmining.model;

import com.abhi.processmining.model.Move;

public record AlignmentStep(
        Move move,
        String expected,
        String actual
) {}