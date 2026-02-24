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
 * Detects low-and-slow drip structuring — many individually normal transactions
 * that sum to an anomalously large daily total.
 *
 * Logic: If today's cumulative amount exceeds the client's EWMA daily amount
 * by more than variancePct%, the rule triggers.
 *
 * Guards: Requires at least minDays completed days of history for a meaningful baseline.
 *
 * Scoring: 50 at threshold, scales linearly to 100 at 2x excess.
 */
@Component
public class DailyCumulativeAmountEvaluator implements RuleEvaluator {

    private final RiskThresholdConfig config;

    public DailyCumulativeAmountEvaluator(RiskThresholdConfig config) {
        this.config = config;
    }

    @Override
    public RuleType getSupportedRuleType() {
        return RuleType.DAILY_CUMULATIVE_AMOUNT;
    }

    @Override
    public RuleResult evaluate(Transaction txn, ClientProfile profile, AnomalyRule rule, EvaluationContext context) {
        int minDays = (int) rule.getParamAsLong("minDays",
                config.getRuleDefaults().getDailyCumulativeMinDays());

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
                : config.getRuleDefaults().getDailyCumulativeVariancePct();

        double threshold = ewmaDailyAmount * (1.0 + variancePct / 100.0);
        double currentDailyAmount = context.getCurrentDailyAmountPaise() / 100.0;

        if (currentDailyAmount <= threshold) {
            return notTriggered(rule,
                    String.format("Daily total=%.2f INR (threshold=%.2f, EWMA=%.2f). Within normal range.",
                            currentDailyAmount, threshold, ewmaDailyAmount));
        }

        double excess = currentDailyAmount - threshold;
        double allowedRange = ewmaDailyAmount * (variancePct / 100.0);
        double deviationPct = (allowedRange > 0) ? (excess / allowedRange) * 100.0 : 100.0;
        double partialScore = Math.min(100.0, 50.0 + (deviationPct / 100.0) * 50.0);

        String reason = String.format(
                "Daily cumulative amount anomaly: today's total=%.2f INR, EWMA daily=%.2f, " +
                        "threshold (%.0f%% variance)=%.2f. Exceeded by %.1f%%. " +
                        "Possible low-and-slow drip structuring.",
                currentDailyAmount, ewmaDailyAmount, variancePct, threshold, deviationPct);

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
