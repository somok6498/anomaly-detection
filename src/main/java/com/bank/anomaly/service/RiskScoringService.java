package com.bank.anomaly.service;

import com.bank.anomaly.config.RiskThresholdConfig;
import com.bank.anomaly.model.EvaluationResult;
import com.bank.anomaly.model.RiskLevel;
import com.bank.anomaly.model.RuleResult;
import com.bank.anomaly.model.Transaction;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Computes the composite risk score from individual rule results.
 * Uses weighted average normalization and determines the final action
 * (PASS / ALERT / BLOCK) based on configurable thresholds.
 */
@Service
public class RiskScoringService {

    private final RiskThresholdConfig thresholdConfig;

    public RiskScoringService(RiskThresholdConfig thresholdConfig) {
        this.thresholdConfig = thresholdConfig;
    }

    /**
     * Compute the final evaluation result from individual rule results.
     *
     * Only TRIGGERED rules contribute to the composite score.
     * Composite score = Σ(triggeredPartialScore × riskWeight) / Σ(triggeredRiskWeight)
     * Non-triggered rules appear in the results for transparency but don't dilute the score.
     */
    public EvaluationResult computeResult(Transaction txn, List<RuleResult> ruleResults) {
        if (ruleResults.isEmpty()) {
            return EvaluationResult.builder()
                    .txnId(txn.getTxnId())
                    .clientId(txn.getClientId())
                    .compositeScore(0.0)
                    .riskLevel(RiskLevel.LOW)
                    .action("PASS")
                    .ruleResults(ruleResults)
                    .evaluatedAt(System.currentTimeMillis())
                    .build();
        }

        double weightedScoreSum = 0.0;
        double triggeredWeight = 0.0;

        for (RuleResult result : ruleResults) {
            if (result.isTriggered()) {
                weightedScoreSum += result.getPartialScore() * result.getRiskWeight();
                triggeredWeight += result.getRiskWeight();
            }
        }

        double compositeScore = (triggeredWeight > 0)
                ? Math.min(100.0, weightedScoreSum / triggeredWeight)
                : 0.0;

        RiskLevel riskLevel = RiskLevel.fromScore(compositeScore);
        String action = determineAction(compositeScore);

        return EvaluationResult.builder()
                .txnId(txn.getTxnId())
                .clientId(txn.getClientId())
                .compositeScore(Math.round(compositeScore * 100.0) / 100.0) // round to 2 decimal
                .riskLevel(riskLevel)
                .action(action)
                .ruleResults(ruleResults)
                .evaluatedAt(System.currentTimeMillis())
                .build();
    }

    private String determineAction(double compositeScore) {
        if (compositeScore >= thresholdConfig.getBlockThreshold()) {
            return "BLOCK";
        } else if (compositeScore >= thresholdConfig.getAlertThreshold()) {
            return "ALERT";
        }
        return "PASS";
    }
}
