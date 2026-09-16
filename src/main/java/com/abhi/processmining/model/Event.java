package com.abhi.processmining.model;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String activity;

    @Column(nullable = false)
    private Instant timestamp;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "process_case_id", nullable = false)
    private ProcessCase processCase;

    protected Event() {
    }

    public Event(String activity, Instant timestamp) {
        this.activity = activity;
        this.timestamp = timestamp;
    }

    public Long getId() {
        return id;
    }

    public String getActivity() {
        return activity;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public ProcessCase getProcessCase() {
        return processCase;
    }

    public void setProcessCase(ProcessCase processCase) {
        this.processCase = processCase;
    }
}