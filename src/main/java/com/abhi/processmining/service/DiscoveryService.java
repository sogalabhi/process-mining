package com.abhi.processmining.service;

import com.abhi.processmining.dto.EventResponse;
import com.abhi.processmining.model.Event;
import com.abhi.processmining.repository.EventRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Comparator;
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

}