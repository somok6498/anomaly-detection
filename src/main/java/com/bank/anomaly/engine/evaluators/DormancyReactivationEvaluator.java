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
 * Detects sudden activity after an extended period of inactivity (dormant account reactivation).
 *
 * Logic: If the gap between the current transaction and the client's last activity
 * exceeds the dormancy threshold, the rule triggers.
 *
 * Parameters (priority order):
 *   1. rule.params["dormancyMinutes"] — for testing with short windows
 *   2. rule.params["dormancyDays"] — overrides config default
 *   3. config.ruleDefaults.dormancyDays — application.yml default (30 days)
 *
 * Guards: Skips new clients (totalTxnCount < 2) and clients with no lastUpdated.
 *
 * Scoring: 50 at threshold, scales to 100 at 3x the dormancy period.
 */
@Component
public class DormancyReactivationEvaluator implements RuleEvaluator {

    private final RiskThresholdConfig config;

    public DormancyReactivationEvaluator(RiskThresholdConfig config) {
        this.config = config;
    }

    @Override
    public RuleType getSupportedRuleType() {
        return RuleType.DORMANCY_REACTIVATION;
    }

    @Override
    public RuleResult evaluate(Transaction txn, ClientProfile profile, AnomalyRule rule, EvaluationContext context) {
        // Skip new clients — dormancy is only meaningful for established accounts
        if (profile.getTotalTxnCount() < 2) {
            return notTriggered(rule, "New client — dormancy check not applicable.");
        }

        long lastUpdated = profile.getLastUpdated();
        if (lastUpdated <= 0) {
            return notTriggered(rule, "No last activity timestamp available.");
        }

        // Determine dormancy threshold in milliseconds
        // Priority: dormancyMinutes (for testing) > dormancyDays (per-rule) > config default
        long dormancyMs;
        long dormancyMinutes = rule.getParamAsLong("dormancyMinutes", -1);
        if (dormancyMinutes > 0) {
            dormancyMs = dormancyMinutes * 60 * 1000L;
        } else {
            long dormancyDays = rule.getParamAsLong("dormancyDays",
                    config.getRuleDefaults().getDormancyDays());
            dormancyMs = dormancyDays * 24 * 60 * 60 * 1000L;
        }

        long gapMs = txn.getTimestamp() - lastUpdated;

        if (gapMs < dormancyMs) {
            double gapDays = gapMs / (24.0 * 60 * 60 * 1000);
            return notTriggered(rule,
                    String.format("Last activity %.1f days ago. Below dormancy threshold.", gapDays));
        }

        // Triggered: account has been dormant
        double gapDays = gapMs / (24.0 * 60 * 60 * 1000);
        double dormancyDays = dormancyMs / (24.0 * 60 * 60 * 1000);
        double ratio = gapMs / (double) dormancyMs;

        // Score: 50 at threshold, scales to 100 at 3x dormancy
        double partialScore = Math.min(100.0, 50.0 * (ratio / 1.5));
        double deviationPct = (ratio - 1.0) * 100.0;

        String reason;
        if (dormancyMinutes > 0) {
            reason = String.format(
                    "Dormancy reactivation: account inactive for %.1f minutes (threshold=%d minutes). " +
                            "Sudden reactivation after extended dormancy.",
                    gapMs / (60.0 * 1000), dormancyMinutes);
        } else {
            reason = String.format(
                    "Dormancy reactivation: account inactive for %.1f days (threshold=%.0f days). " +
                            "Sudden reactivation after extended dormancy.",
                    gapDays, dormancyDays);
        }

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
