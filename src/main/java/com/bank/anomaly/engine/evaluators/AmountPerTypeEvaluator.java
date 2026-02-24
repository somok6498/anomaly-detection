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
 * Detects when a transaction amount is anomalous for its specific transaction type.
 *
 * Logic: If txnAmount > avgAmountForType * (1 + variancePct/100), flag it.
 * This catches cases like: client normally does NEFT of ~10,000 but suddenly
 * does a NEFT of 500,000.
 *
 * Falls back gracefully if the client has no history for this specific type.
 */
@Component
public class AmountPerTypeEvaluator implements RuleEvaluator {

    private final RiskThresholdConfig config;

    public AmountPerTypeEvaluator(RiskThresholdConfig config) {
        this.config = config;
    }

    @Override
    public RuleType getSupportedRuleType() {
        return RuleType.AMOUNT_PER_TYPE_ANOMALY;
    }

    @Override
    public RuleResult evaluate(Transaction txn, ClientProfile profile, AnomalyRule rule, EvaluationContext context) {
        String txnType = txn.getTxnType();
        long typeCount = profile.getAmountCountByType().getOrDefault(txnType, 0L);

        // Need sufficient history for this type
        long minSamples = rule.getParamAsLong("minTypeSamples", config.getRuleDefaults().getMinTypeSamples());
        if (typeCount < minSamples) {
            return notTriggered(rule);
        }

        double avgForType = profile.getAvgAmountByType().getOrDefault(txnType, 0.0);
        if (avgForType <= 0) {
            return notTriggered(rule);
        }

        double variancePct = rule.getVariancePct() > 0
                ? rule.getVariancePct()
                : config.getRuleDefaults().getAmountPerTypeVariancePct();
        double threshold = avgForType * (1.0 + variancePct / 100.0);

        if (txn.getAmount() <= threshold) {
            return notTriggered(rule);
        }

        double excess = txn.getAmount() - threshold;
        double allowedRange = avgForType * (variancePct / 100.0);
        double deviationPct = (allowedRange > 0) ? (excess / allowedRange) * 100.0 : 100.0;
        double partialScore = Math.min(100.0, deviationPct);

        String reason = String.format(
                "Amount anomaly for type %s: txn amount=%.2f, avg amount for %s=%.2f, " +
                        "threshold (%.0f%% variance)=%.2f. Exceeded by %.1f%%.",
                txnType, txn.getAmount(), txnType, avgForType, variancePct, threshold, deviationPct);

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
                .reason("Amount for this transaction type is within normal range")
                .build();
    }
}
