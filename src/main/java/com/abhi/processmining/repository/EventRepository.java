package com.abhi.processmining.repository;

import com.abhi.processmining.model.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface EventRepository
        extends JpaRepository<Event, Long> {

    @Query("select e from Event e join fetch e.processCase")
    List<Event> findAllWithProcessCase();
}