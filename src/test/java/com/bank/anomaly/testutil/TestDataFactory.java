package com.bank.anomaly.testutil;

import com.bank.anomaly.model.*;

import java.util.List;

/**
 * Shared test data builders to avoid repeating construction boilerplate across test classes.
 */
public final class TestDataFactory {

    private TestDataFactory() {}

    public static Transaction createTransaction(String txnId, String clientId, String txnType, double amount) {
        return Transaction.builder()
                .txnId(txnId)
                .clientId(clientId)
                .txnType(txnType)
                .amount(amount)
                .timestamp(System.currentTimeMillis())
                .beneficiaryAccount("1234567890")
                .beneficiaryIfsc("HDFC0001234")
                .build();
    }

    public static EvaluationResult createEvaluationResult(String txnId, String clientId, double score, String action) {
        return EvaluationResult.builder()
                .txnId(txnId)
                .clientId(clientId)
                .compositeScore(score)
                .riskLevel(RiskLevel.fromScore(score))
                .action(action)
                .ruleResults(List.of(createRuleResult("RULE-1", true, score, 1.0)))
                .evaluatedAt(System.currentTimeMillis())
                .build();
    }

    public static RuleResult createRuleResult(String ruleId, boolean triggered, double partialScore, double riskWeight) {
        return RuleResult.builder()
                .ruleId(ruleId)
                .ruleName("Test Rule " + ruleId)
                .ruleType(RuleType.AMOUNT_ANOMALY)
                .triggered(triggered)
                .deviationPct(triggered ? 150.0 : 0.0)
                .partialScore(partialScore)
                .riskWeight(riskWeight)
                .reason(triggered ? "Anomaly detected" : "Within normal range")
                .build();
    }

    public static AnomalyRule createAnomalyRule(String ruleId, String name, RuleType type, boolean enabled) {
        return AnomalyRule.builder()
                .ruleId(ruleId)
                .name(name)
                .description("Test rule: " + name)
                .ruleType(type)
                .variancePct(100.0)
                .riskWeight(1.0)
                .enabled(enabled)
                .build();
    }

    public static ClientProfile createClientProfile(String clientId, long txnCount) {
        return ClientProfile.builder()
                .clientId(clientId)
                .totalTxnCount(txnCount)
                .ewmaAmount(50000.0)
                .amountM2(1000000.0)
                .ewmaHourlyTps(8.0)
                .tpsM2(50.0)
                .completedHoursCount(720)
                .build();
    }

    public static ReviewQueueItem createReviewQueueItem(String txnId, String clientId, ReviewStatus status) {
        return ReviewQueueItem.builder()
                .txnId(txnId)
                .clientId(clientId)
                .action("ALERT")
                .compositeScore(55.0)
                .riskLevel("HIGH")
                .triggeredRuleIds(List.of("RULE-1", "RULE-2"))
                .enqueuedAt(System.currentTimeMillis())
                .feedbackStatus(status)
                .feedbackAt(status == ReviewStatus.PENDING ? 0 : System.currentTimeMillis())
                .feedbackBy(status == ReviewStatus.PENDING ? null : "ops")
                .autoAcceptDeadline(System.currentTimeMillis() + 3600000)
                .build();
    }

    public static RuleWeightChange createRuleWeightChange(String ruleId, double oldWeight, double newWeight) {
        return RuleWeightChange.builder()
                .ruleId(ruleId)
                .oldWeight(oldWeight)
                .newWeight(newWeight)
                .tpCount(10)
                .fpCount(3)
                .tpFpRatio(3.33)
                .adjustedAt(System.currentTimeMillis())
                .build();
    }

    public static RulePerformance createRulePerformance(String ruleId, String name, int tp, int fp) {
        int total = tp + fp;
        return RulePerformance.builder()
                .ruleId(ruleId)
                .ruleName(name)
                .ruleType("AMOUNT_ANOMALY")
                .currentWeight(1.0)
                .triggerCount(total)
                .tpCount(tp)
                .fpCount(fp)
                .precision(total > 0 ? (double) tp / total : 0.0)
                .build();
    }

    public static NetworkGraph createNetworkGraph() {
        return NetworkGraph.builder()
                .nodes(List.of(
                        NetworkGraph.NetworkNode.builder().id("CLIENT-007").label("CLIENT-007").type("CLIENT").isCenter(true).build(),
                        NetworkGraph.NetworkNode.builder().id("BENE-1").label("BENE-1").type("BENEFICIARY").fanIn(3).build()
                ))
                .edges(List.of(
                        NetworkGraph.NetworkEdge.builder().from("CLIENT-007").to("BENE-1").build()
                ))
                .build();
    }

    public static ReviewQueueDetail createReviewQueueDetail(String txnId, String clientId) {
        return ReviewQueueDetail.builder()
                .queueItem(createReviewQueueItem(txnId, clientId, ReviewStatus.PENDING))
                .evaluation(createEvaluationResult(txnId, clientId, 55.0, "ALERT"))
                .transaction(createTransaction(txnId, clientId, "NEFT", 50000.0))
                .clientProfile(createClientProfile(clientId, 5000))
                .build();
    }
}
