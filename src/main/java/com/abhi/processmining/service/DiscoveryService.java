package com.abhi.processmining.service;

import com.abhi.processmining.dto.DfgResponse;
import com.abhi.processmining.dto.EventResponse;
import com.abhi.processmining.dto.TransitionResponse;
import com.abhi.processmining.model.CaseTrace;
import com.abhi.processmining.model.Event;
import com.abhi.processmining.model.Transition;
import com.abhi.processmining.repository.EventRepository;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class DiscoveryService {

    private final EventRepository eventRepository;
    private final EventLogService eventLogService;

    public DiscoveryService(EventRepository eventRepository, EventLogService eventLogService) {
        this.eventRepository = eventRepository;
        this.eventLogService = eventLogService;
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

    public List<CaseTrace> getTraces() {
        return eventLogService.loadCaseTraces();
    }

    public DfgResponse getDfg() {
        return buildDfg(eventLogService.loadCaseTraces());
    }

    public List<Transition> directlyFollows(List<String> trace) {
        List<Transition> pairs = new ArrayList<>();

        for (int i = 0; i < trace.size() - 1; i++) {
            pairs.add(new Transition(trace.get(i), trace.get(i + 1)));
        }

        return pairs;
    }

    public Map<Transition, Integer> countTransitions(List<CaseTrace> traces) {
        Map<Transition, Integer> counts = new HashMap<>();

        for (CaseTrace trace : traces) {
            for (Transition transition : directlyFollows(trace.activities())) {
                counts.merge(transition, 1, Integer::sum);
            }
        }

        return counts;
    }

    public DfgResponse buildDfg(List<CaseTrace> traces) {
        Map<Transition, Integer> counts = countTransitions(traces);

        List<TransitionResponse> transitions = counts.entrySet().stream()
                .map(entry -> new TransitionResponse(
                        entry.getKey().from(),
                        entry.getKey().to(),
                        entry.getValue()
                ))
                .sorted(
                        Comparator.comparingInt(TransitionResponse::count).reversed().
                                thenComparing(TransitionResponse::from).
                                thenComparing(TransitionResponse::to)
                )
                .toList();

        List<String> activities = traces.stream()
                .flatMap(trace -> trace.activities().stream())
                .distinct()
                .sorted()
                .toList();

        Map<String, Integer> startActivities = countStartActivities(traces);
        Map<String, Integer> endActivities = countEndActivities(traces);

        return new DfgResponse(activities, transitions, startActivities, endActivities);

    }

    public Map<String, Integer> countStartActivities(List<CaseTrace> traces) {
        Map<String, Integer> counts = new HashMap<>();

        for (CaseTrace trace : traces) {
            counts.merge(trace.first().activity(), 1, Integer::sum);
        }

        return counts;
    }

    public Map<String, Integer> countEndActivities(List<CaseTrace> traces) {
        Map<String, Integer> counts = new HashMap<>();

        for (CaseTrace trace : traces) {
            counts.merge(trace.last().activity(), 1, Integer::sum);
        }

        return counts;
    }
}
