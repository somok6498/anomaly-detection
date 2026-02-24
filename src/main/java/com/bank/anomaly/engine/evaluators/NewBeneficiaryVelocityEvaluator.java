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
 * Detects round-robin mule fan-out — too many first-time beneficiaries in a single day.
 *
 * Two-tier detection:
 *   1. Hard threshold: if daily new beneficiaries >= maxNewBenePerDay, always triggers.
 *      This works even without profile history.
 *   2. Statistical: if new-bene count exceeds EWMA + variancePct%, triggers with
 *      softer scoring. Only activates with sufficient history (minProfileDays).
 *
 * Scoring: 50 at threshold, scales to 100 at 2x threshold.
 */
@Component
public class NewBeneficiaryVelocityEvaluator implements RuleEvaluator {

    private final RiskThresholdConfig config;

    public NewBeneficiaryVelocityEvaluator(RiskThresholdConfig config) {
        this.config = config;
    }

    @Override
    public RuleType getSupportedRuleType() {
        return RuleType.NEW_BENEFICIARY_VELOCITY;
    }

    @Override
    public RuleResult evaluate(Transaction txn, ClientProfile profile, AnomalyRule rule, EvaluationContext context) {
        long dailyNewBeneCount = context.getCurrentDailyNewBeneficiaryCount();

        int maxPerDay = (int) rule.getParamAsLong("maxNewBenePerDay",
                config.getRuleDefaults().getNewBeneMaxPerDay());
        double variancePct = rule.getVariancePct() > 0
                ? rule.getVariancePct()
                : config.getRuleDefaults().getNewBeneVariancePct();
        int minProfileDays = (int) rule.getParamAsLong("minProfileDays",
                config.getRuleDefaults().getNewBeneMinProfileDays());

        // Tier 1: Hard threshold (works without history)
        if (dailyNewBeneCount >= maxPerDay) {
            double ratio = (double) dailyNewBeneCount / maxPerDay;
            double partialScore = Math.min(100.0, 50.0 * ratio);
            double deviationPct = (ratio - 1.0) * 100.0;

            String reason = String.format(
                    "New beneficiary velocity: %d new beneficiaries today (hard limit=%d). " +
                            "Possible round-robin mule fan-out pattern.",
                    dailyNewBeneCount, maxPerDay);

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

        // Tier 2: Statistical (requires history)
        if (profile.getCompletedDaysForBeneCount() < minProfileDays) {
            return notTriggered(rule,
                    String.format("Only %d completed days for bene stats (need %d). Insufficient history.",
                            profile.getCompletedDaysForBeneCount(), minProfileDays));
        }

        double ewmaNewBene = profile.getEwmaDailyNewBeneficiaries();
        if (ewmaNewBene <= 0 && dailyNewBeneCount <= 1) {
            return notTriggered(rule, "No new-beneficiary baseline and count is low.");
        }

        double threshold = Math.max(1.0, ewmaNewBene * (1.0 + variancePct / 100.0));

        if (dailyNewBeneCount <= threshold) {
            return notTriggered(rule,
                    String.format("New bene count=%d (threshold=%.1f, EWMA=%.1f). Within normal range.",
                            dailyNewBeneCount, threshold, ewmaNewBene));
        }

        double excess = dailyNewBeneCount - threshold;
        double allowedRange = Math.max(1.0, ewmaNewBene * (variancePct / 100.0));
        double deviationPct = (excess / allowedRange) * 100.0;
        double partialScore = Math.min(100.0, 50.0 + (deviationPct / 100.0) * 50.0);

        String reason = String.format(
                "New beneficiary velocity (statistical): %d new beneficiaries today, " +
                        "EWMA daily=%.1f, threshold (%.0f%% variance)=%.1f. Exceeded by %.1f%%.",
                dailyNewBeneCount, ewmaNewBene, variancePct, threshold, deviationPct);

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
