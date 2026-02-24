package com.bank.anomaly.engine.evaluators;

import com.bank.anomaly.config.RiskThresholdConfig;
import com.bank.anomaly.engine.EvaluationContext;
import com.bank.anomaly.engine.RuleEvaluator;
import com.bank.anomaly.model.AnomalyRule;
import com.bank.anomaly.model.ClientProfile;
import com.bank.anomaly.model.RuleResult;
import com.bank.anomaly.model.RuleType;
import com.bank.anomaly.model.Transaction;
import org.springframework.stereotype.Component;

/**
 * Detects when the same beneficiary receives a large total amount across
 * multiple transaction types (channels) in a single day.
 *
 * This catches cross-channel splitting: e.g., sending 1L via NEFT + 1L via RTGS +
 * 1L via UPI to the same beneficiary — each individually normal but the daily
 * total to that beneficiary is anomalously high.
 *
 * Logic: If today's total amount to the current beneficiary (across all txn types)
 * exceeds the client's EWMA daily amount by variancePct%, the rule triggers.
 *
 * Guards: Requires beneficiary key, sufficient daily history (minDays).
 *
 * Scoring: 50 at threshold, scales linearly to 100 at 2x excess.
 */
@Component
public class CrossChannelBeneficiaryAmountEvaluator implements RuleEvaluator {

    private final RiskThresholdConfig config;

    public CrossChannelBeneficiaryAmountEvaluator(RiskThresholdConfig config) {
        this.config = config;
    }

    @Override
    public RuleType getSupportedRuleType() {
        return RuleType.CROSS_CHANNEL_BENEFICIARY_AMOUNT;
    }

    @Override
    public RuleResult evaluate(Transaction txn, ClientProfile profile, AnomalyRule rule, EvaluationContext context) {
        // Skip if no beneficiary data
        String beneKey = context.getCurrentBeneficiaryKey();
        if (beneKey == null || beneKey.isBlank()) {
            return notTriggered(rule, "No beneficiary data present.");
        }

        int minDays = (int) rule.getParamAsLong("minDays",
                config.getRuleDefaults().getCrossChannelBeneMinDays());

        if (profile.getCompletedDaysCount() < minDays) {
            return notTriggered(rule,
                    String.format("Only %d completed days (need %d). Insufficient daily history.",
                            profile.getCompletedDaysCount(), minDays));
        }

        double ewmaDailyAmount = profile.getEwmaDailyAmount();
        if (ewmaDailyAmount <= 0) {
            return notTriggered(rule, "No daily amount baseline established yet.");
        }

        double variancePct = rule.getVariancePct() > 0
                ? rule.getVariancePct()
                : config.getRuleDefaults().getCrossChannelBeneVariancePct();

        // Daily total to THIS beneficiary across ALL txn types
        double dailyBeneAmount = context.getCurrentDailyBeneficiaryAmountPaise() / 100.0;
        double threshold = ewmaDailyAmount * (1.0 + variancePct / 100.0);

        if (dailyBeneAmount <= threshold) {
            return notTriggered(rule,
                    String.format("Daily bene total=%.2f INR to %s (threshold=%.2f). Within normal range.",
                            dailyBeneAmount, beneKey, threshold));
        }

        double excess = dailyBeneAmount - threshold;
        double allowedRange = ewmaDailyAmount * (variancePct / 100.0);
        double deviationPct = (allowedRange > 0) ? (excess / allowedRange) * 100.0 : 100.0;
        double partialScore = Math.min(100.0, 50.0 + (deviationPct / 100.0) * 50.0);

        String reason = String.format(
                "Cross-channel beneficiary amount: daily total to %s = %.2f INR " +
                        "(EWMA daily=%.2f, threshold=%.2f at %.0f%% variance). " +
                        "Large cumulative amount to single beneficiary across channels.",
                beneKey, dailyBeneAmount, ewmaDailyAmount, threshold, variancePct);

        return RuleResult.builder()
                .ruleId(rule.getRuleId())
                .ruleName(rule.getName())
                .ruleType(rule.getRuleType())
                .triggered(true)
                .deviationPct(deviationPct)
                .partialScore(partialScore)
                .riskWeight(rule.getRiskWeight())
                .reason(reason)
                .build();
    }

    private RuleResult notTriggered(AnomalyRule rule, String reason) {
        return RuleResult.builder()
                .ruleId(rule.getRuleId())
                .ruleName(rule.getName())
                .ruleType(rule.getRuleType())
                .triggered(false)
                .deviationPct(0.0)
                .partialScore(0.0)
                .riskWeight(rule.getRiskWeight())
                .reason(reason)
                .build();
    }
}
