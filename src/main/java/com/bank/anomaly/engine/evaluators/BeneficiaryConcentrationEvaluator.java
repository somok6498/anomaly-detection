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
 * Detects unusual concentration of transactions to a single beneficiary.
 *
 * Compares the actual concentration (% of total txns going to one beneficiary)
 * against the expected uniform distribution (1 / distinctBeneficiaryCount).
 *
 * Only activates once the client has enough distinct beneficiaries — with fewer,
 * natural concentration is expected and not suspicious.
 *
 * The variancePct parameter controls sensitivity: with variancePct=200,
 * the rule triggers when actual concentration exceeds 3x the expected uniform
 * level (i.e., 1 + 200/100 = 3x).
 */
@Component
public class BeneficiaryConcentrationEvaluator implements RuleEvaluator {

    private final RiskThresholdConfig config;

    public BeneficiaryConcentrationEvaluator(RiskThresholdConfig config) {
        this.config = config;
    }

    @Override
    public RuleType getSupportedRuleType() {
        return RuleType.BENEFICIARY_CONCENTRATION;
    }

    @Override
    public RuleResult evaluate(Transaction txn, ClientProfile profile, AnomalyRule rule, EvaluationContext context) {
        // Skip if no beneficiary data
        String beneKey = context.getCurrentBeneficiaryKey();
        if (beneKey == null || beneKey.isBlank()) {
            return notTriggered(rule, "No beneficiary data present");
        }

        // Need enough distinct beneficiaries for meaningful comparison
        int minDistinctBene = (int) rule.getParamAsLong("minDistinctBeneficiaries",
                config.getRuleDefaults().getMinDistinctBeneficiaries());
        long distinctCount = profile.getDistinctBeneficiaryCount();
        if (distinctCount < minDistinctBene) {
            return notTriggered(rule,
                    String.format("Only %d distinct beneficiaries (min %d). Skipping concentration check.",
                            distinctCount, minDistinctBene));
        }

        double actualConcentration = profile.getBeneficiaryConcentration(beneKey);
        double expectedUniform = 1.0 / distinctCount;

        // Threshold: expected * (1 + variancePct/100)
        double variancePct = rule.getVariancePct() > 0
                ? rule.getVariancePct()
                : config.getRuleDefaults().getBeneficiaryConcentrationVariancePct();
        double threshold = expectedUniform * (1.0 + variancePct / 100.0);

        // Also check absolute minimum concentration to avoid false positives on tiny percentages
        double absMinPct = rule.getParamAsDouble("absMinConcentrationPct",
                config.getRuleDefaults().getAbsMinConcentrationPct());

        if (actualConcentration <= threshold || actualConcentration * 100.0 < absMinPct) {
            return notTriggered(rule,
                    String.format("Beneficiary %s concentration=%.1f%% (threshold=%.1f%%, uniform=%.1f%%). Normal.",
                            beneKey, actualConcentration * 100, threshold * 100, expectedUniform * 100));
        }

        // How far above the threshold
        double excess = actualConcentration - threshold;
        double allowedRange = expectedUniform * (variancePct / 100.0);
        double deviationPct = allowedRange > 0 ? (excess / allowedRange) * 100.0 : 100.0;
        double partialScore = Math.min(100.0, deviationPct);

        long beneTxnCount = profile.getBeneficiaryTxnCounts().getOrDefault(beneKey, 0L);

        String reason = String.format(
                "Beneficiary concentration anomaly: %s has %.1f%% of all txns (%d/%d), " +
                        "expected uniform=%.1f%%, threshold=%.1f%%. " +
                        "Disproportionate volume to single beneficiary.",
                beneKey, actualConcentration * 100, beneTxnCount, profile.getTotalTxnCount(),
                expectedUniform * 100, threshold * 100);

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
