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
 * Detects repeated identical/near-identical amounts to the same beneficiary over time.
 *
 * This catches structuring patterns where a client sends the same amount
 * (e.g., exactly ₹49,999) to the same beneficiary repeatedly — a common
 * tactic to stay under reporting thresholds.
 *
 * Logic: Computes the Coefficient of Variation (CV = stdDev / mean) for amounts
 * to the current beneficiary. A very low CV (e.g., < 10%) indicates amounts are
 * suspiciously uniform. The current transaction must also fall within the
 * repetition band (close to the mean) to trigger.
 *
 * Scoring: CV of 0% (perfectly identical) → score 100. CV at threshold → score 50.
 * riskWeight: 2.5 (structuring for threshold evasion is an AML concern).
 */
@Component
public class BeneficiaryAmountRepetitionEvaluator implements RuleEvaluator {

    private final RiskThresholdConfig config;

    public BeneficiaryAmountRepetitionEvaluator(RiskThresholdConfig config) {
        this.config = config;
    }

    @Override
    public RuleType getSupportedRuleType() {
        return RuleType.BENEFICIARY_AMOUNT_REPETITION;
    }

    @Override
    public RuleResult evaluate(Transaction txn, ClientProfile profile, AnomalyRule rule, EvaluationContext context) {
        String beneKey = context.getCurrentBeneficiaryKey();
        if (beneKey == null || beneKey.isBlank()) {
            return notTriggered(rule, "No beneficiary data present");
        }

        int minBeneTxns = (int) rule.getParamAsLong("minBeneficiaryTxns",
                config.getRuleDefaults().getMinBeneficiaryTxns());
        double maxCvPct = rule.getParamAsDouble("maxCvPct",
                config.getRuleDefaults().getMaxCvPct());

        long beneCount = profile.getBeneficiaryTxnCounts().getOrDefault(beneKey, 0L);
        if (beneCount < minBeneTxns) {
            return notTriggered(rule,
                    String.format("Beneficiary %s: only %d prior txns (need %d). Insufficient history.",
                            beneKey, beneCount, minBeneTxns));
        }

        Double meanAmount = profile.getEwmaAmountByBeneficiary().get(beneKey);
        if (meanAmount == null || meanAmount <= 0) {
            return notTriggered(rule, String.format("Beneficiary %s: no amount history available.", beneKey));
        }

        double stdDev = profile.getAmountStdDevForBeneficiary(beneKey);
        double cvPct = (stdDev / meanAmount) * 100.0;

        if (cvPct >= maxCvPct) {
            return notTriggered(rule,
                    String.format("Beneficiary %s: CV=%.1f%% (threshold=%.1f%%). Amounts are sufficiently varied.",
                            beneKey, cvPct, maxCvPct));
        }

        // CV is low — amounts are suspiciously uniform. Check if current txn is in the repetition band.
        double tolerance = Math.max(stdDev, meanAmount * 0.05); // at least 5% of mean
        double deviation = Math.abs(txn.getAmount() - meanAmount);

        if (deviation > tolerance) {
            return notTriggered(rule,
                    String.format("Beneficiary %s: CV=%.1f%% is low, but current amount %.2f deviates from mean %.2f by %.2f (tolerance=%.2f). Not a repeat.",
                            beneKey, cvPct, txn.getAmount(), meanAmount, deviation, tolerance));
        }

        // Triggered: low CV + current amount matches the pattern
        double partialScore = Math.max(50.0, 100.0 * (1.0 - cvPct / maxCvPct));
        double deviationPct = ((maxCvPct - cvPct) / maxCvPct) * 100.0;

        String reason = String.format(
                "Repeated amount pattern to beneficiary %s: %d prior txns with CV=%.1f%% (threshold=%.1f%%). " +
                        "Mean amount=%.2f, current=%.2f. Possible structuring to evade reporting thresholds.",
                beneKey, beneCount, cvPct, maxCvPct, meanAmount, txn.getAmount());

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
