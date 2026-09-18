package com.abhi.processmining.controller;

import com.abhi.processmining.dto.DfgResponse;
import com.abhi.processmining.model.CaseTrace;
import com.abhi.processmining.service.DiscoveryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.abhi.processmining.dto.EventResponse;

import java.util.List;

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
    public List<CaseTrace> getTraces() {
        return discoveryService.getTraces();
    }

    @GetMapping("/dfg")
    public DfgResponse getDfg() {
        return discoveryService.getDfg();
    }

}
