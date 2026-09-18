package com.abhi.processmining.service;

import com.abhi.processmining.model.CaseTrace;
import com.abhi.processmining.model.Event;
import com.abhi.processmining.model.TraceEvent;
import com.abhi.processmining.repository.EventRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class EventLogService {

    private final EventRepository eventRepository;

    public EventLogService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    public List<CaseTrace> loadCaseTraces() {
        return toCaseTraces(eventRepository.findAllWithProcessCase());
    }

    public List<CaseTrace> toCaseTraces(List<Event> events) {
        Map<String, List<TraceEvent>> eventsByCase = events.stream()
                .collect(Collectors.groupingBy(
                        event -> event.getProcessCase().getCaseId(),
                        LinkedHashMap::new,
                        Collectors.mapping(
                                event -> new TraceEvent(event.getActivity(), event.getTimestamp()),
                                Collectors.toList()
                        )
                ));

        List<CaseTrace> traces = new ArrayList<>();

        for (Map.Entry<String, List<TraceEvent>> entry : eventsByCase.entrySet()) {
            List<TraceEvent> sorted = entry.getValue().stream()
                    .sorted(Comparator.comparing(TraceEvent::timestamp))
                    .toList();

            traces.add(new CaseTrace(entry.getKey(), sorted));
        }

        return traces;
    }
}
