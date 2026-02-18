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
 * Detects when a single transaction amount is anomalously high compared
 * to the client's historical EWMA amount.
 *
 * Logic: If txnAmount > ewmaAmount * (1 + variancePct/100), flag it.
 * Score is proportional to how far above the threshold the amount is.
 *
 * Example: Client's EWMA amount = 50,000. variancePct=100 means threshold
 * is 100,000. A transaction of 150,000 would score (150k-100k)/(100k-50k)*100 = 100.
 */
@Component
public class AmountAnomalyEvaluator implements RuleEvaluator {

    @Override
    public RuleType getSupportedRuleType() {
        return RuleType.AMOUNT_ANOMALY;
    }

    @Override
    public RuleResult evaluate(Transaction txn, ClientProfile profile, AnomalyRule rule, EvaluationContext context) {
        if (profile.getTotalTxnCount() < 2) {
            return notTriggered(rule);
        }

        double ewmaAmount = profile.getEwmaAmount();
        if (ewmaAmount <= 0) {
            return notTriggered(rule);
        }

        double variancePct = rule.getVariancePct();
        double threshold = ewmaAmount * (1.0 + variancePct / 100.0);

        if (txn.getAmount() <= threshold) {
            return notTriggered(rule);
        }

        double excess = txn.getAmount() - threshold;
        double allowedRange = ewmaAmount * (variancePct / 100.0);
        double deviationPct = (allowedRange > 0) ? (excess / allowedRange) * 100.0 : 100.0;
        double partialScore = Math.min(100.0, deviationPct);

        String reason = String.format(
                "Amount anomaly: txn amount=%.2f, EWMA amount=%.2f, " +
                        "threshold (%.0f%% variance)=%.2f. Exceeded by %.1f%%.",
                txn.getAmount(), ewmaAmount, variancePct, threshold, deviationPct);

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
                .reason("Transaction amount within normal range")
                .build();
    }
}
