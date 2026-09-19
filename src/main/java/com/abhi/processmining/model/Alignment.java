package com.abhi.processmining.model;

import java.util.List;

public record Alignment(List<AlignmentMove> moves, int cost) {

    public Alignment {
        moves = List.copyOf(moves);
    }
}
