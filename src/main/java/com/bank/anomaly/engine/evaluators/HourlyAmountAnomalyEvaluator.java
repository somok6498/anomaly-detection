package com.bank.anomaly.engine.evaluators;

import com.bank.anomaly.engine.EvaluationContext;
import com.bank.anomaly.engine.RuleEvaluator;
import com.bank.anomaly.model.AnomalyRule;
import com.bank.anomaly.model.ClientProfile;
import com.bank.anomaly.model.RuleResult;
import com.bank.anomaly.model.RuleType;
import com.bank.anomaly.model.Transaction;
import org.springframework.stereotype.Component;

/**
 * Detects when the cumulative hourly transaction amount exceeds the client's
 * historical hourly average.
 *
 * Logic: If currentHourAmount > ewmaHourlyAmount * (1 + variancePct/100), flag it.
 *
 * Example: Client's avg hourly amount = 500,000. variancePct=100 means threshold
 * is 1,000,000. If current hour total hits 1,200,000, it's flagged.
 */
@Component
public class HourlyAmountAnomalyEvaluator implements RuleEvaluator {

    @Override
    public RuleType getSupportedRuleType() {
        return RuleType.HOURLY_AMOUNT_ANOMALY;
    }

    @Override
    public RuleResult evaluate(Transaction txn, ClientProfile profile, AnomalyRule rule, EvaluationContext context) {
        if (profile.getCompletedHoursCount() < 2) {
            return notTriggered(rule);
        }

        double ewmaHourlyAmount = profile.getEwmaHourlyAmount();
        if (ewmaHourlyAmount <= 0) {
            return notTriggered(rule);
        }

        // Convert from paise to rupees for comparison
        double currentHourlyAmount = context.getCurrentHourlyAmountPaise() / 100.0;
        double variancePct = rule.getVariancePct();
        double threshold = ewmaHourlyAmount * (1.0 + variancePct / 100.0);

        if (currentHourlyAmount <= threshold) {
            return notTriggered(rule);
        }

        double excess = currentHourlyAmount - threshold;
        double allowedRange = ewmaHourlyAmount * (variancePct / 100.0);
        double deviationPct = (allowedRange > 0) ? (excess / allowedRange) * 100.0 : 100.0;
        double partialScore = Math.min(100.0, deviationPct);

        String reason = String.format(
                "Hourly amount anomaly: current hour total=%.2f, EWMA hourly amount=%.2f, " +
                        "threshold (%.0f%% variance)=%.2f. Exceeded by %.1f%%.",
                currentHourlyAmount, ewmaHourlyAmount, variancePct, threshold, deviationPct);

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

    private RuleResult notTriggered(AnomalyRule rule) {
        return RuleResult.builder()
                .ruleId(rule.getRuleId())
                .ruleName(rule.getName())
                .ruleType(rule.getRuleType())
                .triggered(false)
                .deviationPct(0.0)
                .partialScore(0.0)
                .riskWeight(rule.getRiskWeight())
                .reason("Hourly amount within normal range")
                .build();
    }
}
