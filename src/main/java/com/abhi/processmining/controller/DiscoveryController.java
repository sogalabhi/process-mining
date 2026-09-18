package com.abhi.processmining.controller;

import com.abhi.processmining.model.Event;
import com.abhi.processmining.model.Transition;
import com.abhi.processmining.service.DiscoveryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.abhi.processmining.dto.EventResponse;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/discovery")
public class DiscoveryController {

    private final DiscoveryService discoveryService;

    public DiscoveryController(DiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    @GetMapping("/events")
    public List<EventResponse> getEvents() {
        return discoveryService.getAllEventResponses();
    }

    @GetMapping("/traces")
    public Map<String, List<String>> getGroupedAndSortedEvents() {

        List<Event> events = discoveryService.getAllEvents();
        Map<String, List<Event>> eventsmap = discoveryService.groupEventsByCase(events);
        discoveryService.sortEventsByTimestamp(eventsmap);
        return discoveryService.buildTraces(eventsmap);
    }

    @GetMapping("/transitions")
    public Map<Transition, Integer> getTransitions() {
        List<Event> events = discoveryService.getAllEvents();
        Map<String, List<Event>> eventsByCase = discoveryService.groupEventsByCase(events);
        discoveryService.sortEventsByTimestamp(eventsByCase);
        Map<String, List<String>> traces = discoveryService.buildTraces(eventsByCase);
        return discoveryService.countTransitions(traces);
    }
    
}