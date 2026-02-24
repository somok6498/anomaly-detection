package com.bank.anomaly.engine.evaluators;

import com.bank.anomaly.engine.EvaluationContext;
import com.bank.anomaly.engine.RuleEvaluator;
import com.bank.anomaly.model.AnomalyRule;
import com.bank.anomaly.model.ClientProfile;
import com.bank.anomaly.model.RuleResult;
import com.bank.anomaly.model.RuleType;
import com.bank.anomaly.model.Transaction;
import com.bank.anomaly.config.RiskThresholdConfig;
import org.springframework.stereotype.Component;

/**
 * Detects TPS (transactions per hour) spikes for a client.
 *
 * Logic: Compares the current hour's transaction count against the client's
 * EWMA hourly TPS. If current count exceeds ewma * (1 + variancePct/100),
 * the transaction is flagged.
 *
 * Example: Client averages 500 txns/hour. variancePct=50 means threshold is
 * 750. If current hour hits 900, score = (900-750)/(750-500) * 100 = 60.
 */
@Component
public class TpsSpikeEvaluator implements RuleEvaluator {

    private final RiskThresholdConfig config;

    public TpsSpikeEvaluator(RiskThresholdConfig config) {
        this.config = config;
    }

    @Override
    public RuleType getSupportedRuleType() {
        return RuleType.TPS_SPIKE;
    }

    @Override
    public RuleResult evaluate(Transaction txn, ClientProfile profile, AnomalyRule rule, EvaluationContext context) {
        // Need at least some completed hours to have a baseline
        if (profile.getCompletedHoursCount() < 2) {
            return notTriggered(rule);
        }

        double ewmaTps = profile.getEwmaHourlyTps();
        if (ewmaTps <= 0) {
            return notTriggered(rule);
        }

        long currentCount = context.getCurrentHourlyTxnCount();
        double variancePct = rule.getVariancePct() > 0
                ? rule.getVariancePct()
                : config.getRuleDefaults().getTpsSpikeVariancePct();
        double threshold = ewmaTps * (1.0 + variancePct / 100.0);

        if (currentCount <= threshold) {
            return notTriggered(rule);
        }

        // How far above the threshold?
        double excess = currentCount - threshold;
        double allowedRange = ewmaTps * (variancePct / 100.0);
        double deviationPct = (allowedRange > 0) ? (excess / allowedRange) * 100.0 : 100.0;
        double partialScore = Math.min(100.0, deviationPct);

        String reason = String.format(
                "TPS spike detected: current hour count=%d, EWMA hourly TPS=%.1f, " +
                        "threshold (%.0f%% variance)=%.1f. Exceeded by %.1f%%.",
                currentCount, ewmaTps, variancePct, threshold, deviationPct);

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
                .reason("Hourly TPS within normal range")
                .build();
    }
}
