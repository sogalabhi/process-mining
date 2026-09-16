package com.abhi.processmining.model;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "process_cases")
public class ProcessCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String caseId;

    @OneToMany(
            mappedBy = "processCase",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    private List<Event> events = new ArrayList<>();

    protected ProcessCase() {
    }

    public ProcessCase(String caseId) {
        this.caseId = caseId;
    }

    public Long getId() {
        return id;
    }

    public String getCaseId() {
        return caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public List<Event> getEvents() {
        return events;
    }

    public void addEvent(Event event) {
        events.add(event);
        event.setProcessCase(this);
    }
}