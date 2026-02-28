package com.bank.anomaly.service;

import com.bank.anomaly.config.RiskThresholdConfig;
import com.bank.anomaly.model.EvaluationResult;
import com.bank.anomaly.model.Transaction;
import com.bank.anomaly.model.RuleResult;
import com.bank.anomaly.testutil.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RiskScoringServiceTest {

    private RiskScoringService scoringService;

    @BeforeEach
    void setUp() {
        RiskThresholdConfig config = new RiskThresholdConfig();
        config.setAlertThreshold(30.0);
        config.setBlockThreshold(70.0);
        scoringService = new RiskScoringService(config);
    }

    @Test
    void computeResult_emptyRuleResults_returnsPass() {
        Transaction txn = TestDataFactory.createTransaction("TXN-1", "C-1", "NEFT", 50000);
        EvaluationResult result = scoringService.computeResult(txn, Collections.emptyList());

        assertThat(result.getCompositeScore()).isEqualTo(0.0);
        assertThat(result.getAction()).isEqualTo("PASS");
        assertThat(result.getRiskLevel().name()).isEqualTo("LOW");
    }

    @Test
    void computeResult_noRulesTriggered_returnsPass() {
        Transaction txn = TestDataFactory.createTransaction("TXN-1", "C-1", "NEFT", 50000);
        List<RuleResult> rules = List.of(
                TestDataFactory.createRuleResult("R1", false, 0.0, 1.0),
                TestDataFactory.createRuleResult("R2", false, 0.0, 2.0)
        );
        EvaluationResult result = scoringService.computeResult(txn, rules);

        assertThat(result.getCompositeScore()).isEqualTo(0.0);
        assertThat(result.getAction()).isEqualTo("PASS");
    }

    @Test
    void computeResult_singleTriggeredRule_correctScore() {
        Transaction txn = TestDataFactory.createTransaction("TXN-1", "C-1", "NEFT", 50000);
        List<RuleResult> rules = List.of(
                TestDataFactory.createRuleResult("R1", true, 45.0, 1.0)
        );
        EvaluationResult result = scoringService.computeResult(txn, rules);

        assertThat(result.getCompositeScore()).isCloseTo(45.0, within(0.01));
        assertThat(result.getAction()).isEqualTo("ALERT");
    }

    @Test
    void computeResult_multipleTriggeredRules_weightedAverage() {
        Transaction txn = TestDataFactory.createTransaction("TXN-1", "C-1", "NEFT", 50000);
        List<RuleResult> rules = List.of(
                TestDataFactory.createRuleResult("R1", true, 60.0, 2.0),  // contributes 120
                TestDataFactory.createRuleResult("R2", true, 40.0, 1.0),  // contributes 40
                TestDataFactory.createRuleResult("R3", false, 0.0, 1.0)   // not triggered — ignored
        );
        // Expected: (60*2 + 40*1) / (2+1) = 160/3 = 53.33
        EvaluationResult result = scoringService.computeResult(txn, rules);

        assertThat(result.getCompositeScore()).isCloseTo(53.33, within(0.01));
        assertThat(result.getAction()).isEqualTo("ALERT");
    }

    @Test
    void computeResult_scoreAboveAlertThreshold_returnsAlert() {
        Transaction txn = TestDataFactory.createTransaction("TXN-1", "C-1", "NEFT", 50000);
        List<RuleResult> rules = List.of(
                TestDataFactory.createRuleResult("R1", true, 35.0, 1.0)
        );
        EvaluationResult result = scoringService.computeResult(txn, rules);

        assertThat(result.getCompositeScore()).isCloseTo(35.0, within(0.01));
        assertThat(result.getAction()).isEqualTo("ALERT");
    }

    @Test
    void computeResult_scoreAboveBlockThreshold_returnsBlock() {
        Transaction txn = TestDataFactory.createTransaction("TXN-1", "C-1", "NEFT", 50000);
        List<RuleResult> rules = List.of(
                TestDataFactory.createRuleResult("R1", true, 85.0, 1.0)
        );
        EvaluationResult result = scoringService.computeResult(txn, rules);

        assertThat(result.getCompositeScore()).isCloseTo(85.0, within(0.01));
        assertThat(result.getAction()).isEqualTo("BLOCK");
    }

    @Test
    void computeResult_scoreCappedAt100() {
        Transaction txn = TestDataFactory.createTransaction("TXN-1", "C-1", "NEFT", 50000);
        // partialScore 120 would exceed 100 — should cap
        List<RuleResult> rules = List.of(
                RuleResult.builder()
                        .ruleId("R1").ruleName("Test").ruleType(null)
                        .triggered(true).partialScore(120.0).riskWeight(1.0)
                        .build()
        );
        EvaluationResult result = scoringService.computeResult(txn, rules);

        assertThat(result.getCompositeScore()).isLessThanOrEqualTo(100.0);
    }
}
