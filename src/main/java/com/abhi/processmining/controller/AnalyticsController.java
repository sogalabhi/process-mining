package com.abhi.processmining.controller;

import com.abhi.processmining.dto.analytics.*;
import com.abhi.processmining.service.AnalyticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/variants")
    public List<VariantResponse> getVariants() {
        return analyticsService.getVariants();
    }

    @GetMapping("/durations")
    public CaseDurationsResponse getCaseDurations() {
        return analyticsService.getCaseDurations();
    }

    @GetMapping("/activities")
    public List<ActivityFrequencyResponse> getActivityFrequencies() {
        return analyticsService.getActivityFrequencies();
    }

    @GetMapping("/transitions")
    public List<TransitionStatsResponse> getTransitionStats() {
        return analyticsService.getTransitionStats();
    }

    @GetMapping("/bottlenecks")
    public List<TransitionStatsResponse> getBottlenecks(
            @RequestParam(defaultValue = "3") int top,
            @RequestParam(defaultValue = "2") int minFrequency
    ) {
        return analyticsService.getBottlenecks(top, minFrequency);
    }

    @GetMapping("/rework")
    public ReworkResponse getRework() {
        return analyticsService.getRework();
    }

    @GetMapping("/start-end")
    public StartEndResponse getStartEnd() {
        return analyticsService.getStartEnd();
    }

    @GetMapping("/throughput")
    public ThroughputResponse getThroughput(
            @RequestParam(required = false) String endActivity
    ) {
        return analyticsService.getThroughput(endActivity);
    }
}
