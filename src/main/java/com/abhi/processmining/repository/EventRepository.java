package com.abhi.processmining.repository;

import com.abhi.processmining.model.Event;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository
        extends JpaRepository<Event, Long> {
}