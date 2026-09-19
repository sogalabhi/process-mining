package com.abhi.processmining.controller;

import com.abhi.processmining.dto.conformance.ConformanceRequest;
import com.abhi.processmining.dto.conformance.ConformanceResponse;
import com.abhi.processmining.service.ConformanceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conformance")
public class ConformanceController {

    private final ConformanceService conformanceService;

    public ConformanceController(ConformanceService conformanceService) {
        this.conformanceService = conformanceService;
    }

    @PostMapping
    public ConformanceResponse checkConformance(@Valid @RequestBody ConformanceRequest request) {
        return conformanceService.checkConformance(request.expected());
    }
}
