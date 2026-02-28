package com.bank.anomaly.service;

import com.bank.anomaly.model.*;
import com.bank.anomaly.repository.ReviewQueueRepository;
import com.bank.anomaly.repository.RuleRepository;
import com.bank.anomaly.testutil.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock private ReviewQueueRepository reviewQueueRepository;
    @Mock private RuleRepository ruleRepository;
    @Mock private BeneficiaryGraphService graphService;

    private AnalyticsService analyticsService;

    @BeforeEach
    void setUp() {
        analyticsService = new AnalyticsService(reviewQueueRepository, ruleRepository, graphService);
    }

    @Test
    void getRulePerformanceStats_calculatesCorrectPrecision() {
        AnomalyRule ruleA = TestDataFactory.createAnomalyRule("R-A", "Rule A", RuleType.AMOUNT_ANOMALY, true);
        when(ruleRepository.getAllRulesCached()).thenReturn(List.of(ruleA));

        // 3 TP items and 1 FP item — all triggered R-A
        List<ReviewQueueItem> feedback = List.of(
                ReviewQueueItem.builder().txnId("T1").feedbackStatus(ReviewStatus.TRUE_POSITIVE).triggeredRuleIds(List.of("R-A")).build(),
                ReviewQueueItem.builder().txnId("T2").feedbackStatus(ReviewStatus.TRUE_POSITIVE).triggeredRuleIds(List.of("R-A")).build(),
                ReviewQueueItem.builder().txnId("T3").feedbackStatus(ReviewStatus.TRUE_POSITIVE).triggeredRuleIds(List.of("R-A")).build(),
                ReviewQueueItem.builder().txnId("T4").feedbackStatus(ReviewStatus.FALSE_POSITIVE).triggeredRuleIds(List.of("R-A")).build()
        );
        when(reviewQueueRepository.findAllWithFeedback()).thenReturn(feedback);

        List<RulePerformance> results = analyticsService.getRulePerformanceStats();

        assertThat(results).hasSize(1);
        RulePerformance rp = results.get(0);
        assertThat(rp.getRuleId()).isEqualTo("R-A");
        assertThat(rp.getTpCount()).isEqualTo(3);
        assertThat(rp.getFpCount()).isEqualTo(1);
        assertThat(rp.getPrecision()).isCloseTo(0.75, within(0.001));
    }

    @Test
    void getRulePerformanceStats_noFeedback_returnsZeroPrecision() {
        AnomalyRule ruleA = TestDataFactory.createAnomalyRule("R-A", "Rule A", RuleType.AMOUNT_ANOMALY, true);
        when(ruleRepository.getAllRulesCached()).thenReturn(List.of(ruleA));
        when(reviewQueueRepository.findAllWithFeedback()).thenReturn(Collections.emptyList());

        List<RulePerformance> results = analyticsService.getRulePerformanceStats();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getPrecision()).isEqualTo(0.0);
        assertThat(results.get(0).getTriggerCount()).isEqualTo(0);
    }

    @Test
    void getClientNetwork_graphNotReady_returnsEmptyGraph() {
        when(graphService.isGraphReady()).thenReturn(false);

        NetworkGraph graph = analyticsService.getClientNetwork("C-1");

        assertThat(graph.getNodes()).isEmpty();
        assertThat(graph.getEdges()).isEmpty();
    }

    @Test
    void getClientNetwork_withBeneficiaries_buildsCorrectGraph() {
        when(graphService.isGraphReady()).thenReturn(true);
        when(graphService.getBeneficiariesForClient("C-1")).thenReturn(Set.of("HDFC:1234"));
        when(graphService.getFanInCount("HDFC:1234")).thenReturn(2);
        when(graphService.getOtherSenders("HDFC:1234", "C-1")).thenReturn(Set.of("C-2"));

        NetworkGraph graph = analyticsService.getClientNetwork("C-1");

        // Should have: C-1 (center), HDFC:1234 (bene), C-2 (neighbor)
        assertThat(graph.getNodes()).hasSize(3);
        assertThat(graph.getNodes().stream().filter(n -> n.isCenter()).count()).isEqualTo(1);
        assertThat(graph.getNodes().stream().filter(n -> "BENEFICIARY".equals(n.getType())).count()).isEqualTo(1);
        // Edges: C-1->HDFC:1234, C-2->HDFC:1234
        assertThat(graph.getEdges()).hasSize(2);
    }
}
