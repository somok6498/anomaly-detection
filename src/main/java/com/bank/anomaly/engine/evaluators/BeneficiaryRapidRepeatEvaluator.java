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
 * Detects rapid repeat transactions to the same beneficiary within one hour.
 *
 * This catches structuring/smurfing patterns where a client sends many
 * small transactions to the same beneficiary in a short window to avoid
 * triggering single-transaction amount thresholds.
 *
 * Logic: If the current hour's transaction count to the same beneficiary
 * meets or exceeds minRepeatCount (default 5), the rule triggers.
 *
 * Scoring: 50 at threshold, scales linearly to 100 at 2x threshold.
 * riskWeight: 3.0 (structuring is a serious AML concern).
 */
@Component
public class BeneficiaryRapidRepeatEvaluator implements RuleEvaluator {

    private static final int DEFAULT_MIN_REPEAT_COUNT = 5;

    @Override
    public RuleType getSupportedRuleType() {
        return RuleType.BENEFICIARY_RAPID_REPEAT;
    }

    @Override
    public RuleResult evaluate(Transaction txn, ClientProfile profile, AnomalyRule rule, EvaluationContext context) {
        // Skip if no beneficiary data
        String beneKey = context.getCurrentBeneficiaryKey();
        if (beneKey == null || beneKey.isBlank()) {
            return notTriggered(rule, "No beneficiary data present");
        }

        int minRepeatCount = DEFAULT_MIN_REPEAT_COUNT;
        if (rule.getParams() != null && rule.getParams().containsKey("minRepeatCount")) {
            minRepeatCount = Integer.parseInt(rule.getParams().get("minRepeatCount"));
        }

        long windowCount = context.getCurrentWindowBeneficiaryTxnCount();

        if (windowCount < minRepeatCount) {
            return notTriggered(rule,
                    String.format("Beneficiary %s: %d txns this hour (threshold=%d). Within normal range.",
                            beneKey, windowCount, minRepeatCount));
        }

        // Score: 50 at threshold, scales to 100 at 2x threshold
        double ratio = (double) windowCount / minRepeatCount;
        double partialScore = Math.min(100.0, 50.0 * ratio);

        double deviationPct = (ratio - 1.0) * 100.0;

        double windowAmountRupees = context.getCurrentWindowBeneficiaryAmountPaise() / 100.0;

        String reason = String.format(
                "Rapid repeat to beneficiary %s: %d txns in current hour (threshold=%d), " +
                        "window total=%.2f INR. Possible structuring/smurfing pattern.",
                beneKey, windowCount, minRepeatCount, windowAmountRupees);

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
