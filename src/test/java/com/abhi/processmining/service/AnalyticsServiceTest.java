package com.abhi.processmining.service;

import com.abhi.processmining.dto.analytics.*;
import com.abhi.processmining.dto.analytics.CaseDurationsResponse.CaseDuration;
import com.abhi.processmining.dto.analytics.ReworkResponse.ActivityRework;
import com.abhi.processmining.dto.analytics.StartEndResponse.ActivityShare;
import com.abhi.processmining.dto.analytics.ThroughputResponse.DailyCount;
import com.abhi.processmining.model.CaseTrace;
import com.abhi.processmining.model.TraceEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class AnalyticsServiceTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    private final AnalyticsService service = new AnalyticsService(null);

    @Test
    void variantsGroupIdenticalTracesAndComputePercentages() {
        List<CaseTrace> traces = List.of(
                trace("1", "A", "B", "C"),
                trace("2", "A", "B", "C"),
                trace("3", "A", "C"),
                trace("4", "A")
        );

        assertThat(service.computeVariants(traces)).containsExactly(
                new VariantResponse(List.of("A", "B", "C"), 2, 50.0),
                new VariantResponse(List.of("A"), 1, 25.0),
                new VariantResponse(List.of("A", "C"), 1, 25.0)
        );
    }

    @Test
    void durationStatsUseNearestRankPercentiles() {
        List<Long> oneToTenMinutes = List.of(600L, 60L, 300L, 120L, 540L, 180L, 420L, 240L, 480L, 360L);

        assertThat(AnalyticsService.durationStats(oneToTenMinutes))
                .isEqualTo(new DurationStats(60, 330, 300, 540, 600, 600));
    }

    @Test
    void durationStatsOfOneValueAreAllThatValue() {
        assertThat(AnalyticsService.durationStats(List.of(42L)))
                .isEqualTo(new DurationStats(42, 42, 42, 42, 42, 42));
    }

    @Test
    void caseDurationsIncludeSingleEventCasesAsZero() {
        List<CaseTrace> traces = List.of(
                trace("1", at("A", 0), at("B", 30)),
                trace("2", at("A", 0))
        );

        CaseDurationsResponse result = service.computeCaseDurations(traces);

        assertThat(result.caseCount()).isEqualTo(2);
        assertThat(result.cases()).containsExactly(
                new CaseDuration("1", 1800),
                new CaseDuration("2", 0)
        );
        assertThat(result.stats()).isEqualTo(new DurationStats(0, 900, 0, 1800, 1800, 1800));
    }

    @Test
    void activityFrequenciesCountOccurrencesAndCasesSeparately() {
        List<CaseTrace> traces = List.of(
                trace("1", "A", "B", "B", "B"),
                trace("2", "A", "C")
        );

        assertThat(service.computeActivityFrequencies(traces)).containsExactly(
                new ActivityFrequencyResponse("B", 3, 1, 50.0),
                new ActivityFrequencyResponse("A", 2, 2, 100.0),
                new ActivityFrequencyResponse("C", 1, 1, 50.0)
        );
    }

    @Test
    void transitionStatsMeasureTimeBetweenConsecutiveEvents() {
        List<CaseTrace> traces = List.of(
                trace("1", at("A", 0), at("B", 10), at("C", 40)),
                trace("2", at("A", 0), at("B", 20))
        );

        assertThat(service.computeTransitionStats(traces)).containsExactly(
                new TransitionStatsResponse("A", "B", 2, new DurationStats(600, 900, 600, 1200, 1200, 1200)),
                new TransitionStatsResponse("B", "C", 1, new DurationStats(1800, 1800, 1800, 1800, 1800, 1800))
        );
    }

    @Test
    void bottlenecksIgnoreRareTransitionsAndRankByAverage() {
        List<CaseTrace> traces = List.of(
                trace("1", at("A", 0), at("B", 10), at("C", 100)),
                trace("2", at("A", 0), at("B", 30), at("C", 40)),
                trace("3", at("A", 0), at("D", 500))
        );

        // A→B avg 20 min, B→C avg 50 min, A→D 500 min but seen only once.
        assertThat(service.computeBottlenecks(traces, 3, 2))
                .extracting(TransitionStatsResponse::from, TransitionStatsResponse::to)
                .containsExactly(tuple("B", "C"), tuple("A", "B"));

        assertThat(service.computeBottlenecks(traces, 1, 2))
                .extracting(TransitionStatsResponse::from, TransitionStatsResponse::to)
                .containsExactly(tuple("B", "C"));
    }

    @Test
    void reworkCountsCasesAndRepeatedActivities() {
        List<CaseTrace> traces = List.of(
                trace("1", "A", "B", "B", "C", "B"),
                trace("2", "A", "C", "C"),
                trace("3", "A", "B")
        );

        assertThat(service.computeRework(traces)).isEqualTo(new ReworkResponse(
                3,
                2,
                66.67,
                List.of(
                        new ActivityRework("B", 1, 2),
                        new ActivityRework("C", 1, 1)
                )
        ));
    }

    @Test
    void startEndSharesArePercentagesOfAllCases() {
        List<CaseTrace> traces = List.of(
                trace("1", "A", "B"),
                trace("2", "A", "C"),
                trace("3", "B", "C")
        );

        StartEndResponse result = service.computeStartEnd(traces);

        assertThat(result.caseCount()).isEqualTo(3);
        assertThat(result.startActivities()).containsExactly(
                new ActivityShare("A", 2, 66.67),
                new ActivityShare("B", 1, 33.33)
        );
        assertThat(result.endActivities()).containsExactly(
                new ActivityShare("C", 2, 66.67),
                new ActivityShare("B", 1, 33.33)
        );
    }

    @Test
    void throughputCountsCompletedCasesPerUtcDay() {
        List<CaseTrace> traces = List.of(
                trace("1", at("A", 0), at("Delivered", 60)),
                trace("2", at("A", 0), at("Delivered", 24 * 60 + 30)),
                trace("3", at("A", 0), at("Cancelled", 120))
        );

        ThroughputResponse all = service.computeThroughput(traces, null);
        assertThat(all.completedCases()).isEqualTo(3);
        assertThat(all.perDay()).containsExactly(
                new DailyCount(LocalDate.parse("2026-01-01"), 2),
                new DailyCount(LocalDate.parse("2026-01-02"), 1)
        );

        ThroughputResponse delivered = service.computeThroughput(traces, "Delivered");
        assertThat(delivered.totalCases()).isEqualTo(3);
        assertThat(delivered.completedCases()).isEqualTo(2);
        assertThat(delivered.perDay()).containsExactly(
                new DailyCount(LocalDate.parse("2026-01-01"), 1),
                new DailyCount(LocalDate.parse("2026-01-02"), 1)
        );
    }

    @Test
    void percentageRoundsToTwoDecimalsAndHandlesZeroTotal() {
        assertThat(AnalyticsService.percentage(3, 11)).isEqualTo(27.27);
        assertThat(AnalyticsService.percentage(0, 0)).isEqualTo(0.0);
    }

    private static TraceEvent at(String activity, int minute) {
        return new TraceEvent(activity, BASE.plusSeconds(minute * 60L));
    }

    private static CaseTrace trace(String caseId, TraceEvent... events) {
        return new CaseTrace(caseId, List.of(events));
    }

    private static CaseTrace trace(String caseId, String... activities) {
        List<TraceEvent> events = new ArrayList<>();

        for (int i = 0; i < activities.length; i++) {
            events.add(at(activities[i], i));
        }

        return new CaseTrace(caseId, events);
    }
}
