package com.abhi.processmining.service;

import com.abhi.processmining.dto.DfgResponse;
import com.abhi.processmining.dto.EventResponse;
import com.abhi.processmining.dto.TransitionResponse;
import com.abhi.processmining.model.Event;
import com.abhi.processmining.model.Transition;
import com.abhi.processmining.repository.EventRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class DiscoveryService {

    private final EventRepository eventRepository;

    public DiscoveryService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    public Map<String, List<Event>> groupEventsByCase(List<Event> events) {
    return events.stream()
                .collect(Collectors.groupingBy(
                        event -> event.getProcessCase().getCaseId(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
    }

    public List<Event> getAllEvents() {
        return eventRepository.findAllWithProcessCase();
    }

    public List<EventResponse> getAllEventResponses() {
        return eventRepository.findAllWithProcessCase().stream()
                .map(this::toResponse)
                .toList();
    }

    private EventResponse toResponse(Event event) {
        return new EventResponse(
                event.getProcessCase().getCaseId(),
                event.getActivity(),
                event.getTimestamp()
        );
    }

    public void sortEventsByTimestamp(
            Map<String, List<Event>> eventsByCase) {
        for (List<Event> events : eventsByCase.values()) {
            events.sort(Comparator.comparing(Event::getTimestamp));
        }
    }

    public Map<String, List<String>> buildTraces(
            Map<String, List<Event>> eventsByCase) {
        Map<String, List<String>> traces = new LinkedHashMap<>();

        for (Map.Entry<String, List<Event>> entry : eventsByCase.entrySet()) {
            List<String> activities = entry.getValue().stream()
                    .map(Event::getActivity)
                    .toList();

            traces.put(entry.getKey(), activities);
        }

        return traces;
    }

    public List<Transition> directlyFollows(List<String> trace) {
        List<Transition> pairs = new ArrayList<>();

        for (int i = 0; i < trace.size() - 1; i++) {
            pairs.add(new Transition(trace.get(i), trace.get(i + 1)));
        }

        return pairs;
    }

    public Map<Transition, Integer> countTransitions(Map<String, List<String>> traces) {
        Map<Transition, Integer> counts = new HashMap<>();

        for (List<String> trace : traces.values()) {
            for (Transition transition : directlyFollows(trace)) {
                counts.merge(transition, 1, Integer::sum);
            }
        }

        return counts;
    }

    public DfgResponse buildDfg(Map<String, List<String>> traces) {
        Map<Transition, Integer> counts = countTransitions(traces);

        List<TransitionResponse> transitions = counts.entrySet().stream()
                .map(entry -> new TransitionResponse(
                        entry.getKey().from(),
                        entry.getKey().to(),
                        entry.getValue()
                ))
                .sorted(Comparator.comparingInt(TransitionResponse::count).reversed())
                .toList();

        List<String> activities = traces.values().stream()
                .flatMap(List::stream)
                .distinct()
                .sorted()
                .toList();

        Map<String, Integer> startActivities = countStartActivities(traces);
        Map<String, Integer> endActivities = countEndActivities(traces);

        return new DfgResponse(activities, transitions, startActivities, endActivities);

    }

    public Map<String, List<String>> getTraces() {
        List<Event> events = getAllEvents();
        Map<String, List<Event>> eventsByCase = groupEventsByCase(events);
        sortEventsByTimestamp(eventsByCase);
        return buildTraces(eventsByCase);
    }

    public Map<String, Integer> countStartActivities(Map<String, List<String>> traces) {
        Map<String, Integer> counts = new HashMap<>();

        for (List<String> trace : traces.values()) {
            counts.merge(trace.getFirst(), 1, Integer::sum);
        }

        return counts;
    }

    public Map<String, Integer> countEndActivities(Map<String, List<String>> traces) {
        Map<String, Integer> counts = new HashMap<>();

        for (List<String> trace : traces.values()) {
            counts.merge(trace.getLast(), 1, Integer::sum);
        }

        return counts;
    }
}