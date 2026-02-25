package com.bank.anomaly.engine.evaluators;

import com.bank.anomaly.config.RiskThresholdConfig;
import com.bank.anomaly.engine.EvaluationContext;
import com.bank.anomaly.engine.RuleEvaluator;
import com.bank.anomaly.model.AnomalyRule;
import com.bank.anomaly.model.ClientProfile;
import com.bank.anomaly.model.RuleResult;
import com.bank.anomaly.model.RuleType;
import com.bank.anomaly.model.Transaction;
import com.bank.anomaly.service.BeneficiaryGraphService;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Detects mule network patterns using an in-memory beneficiary graph.
 *
 * Three signals are combined with weighted scoring:
 *   1. Fan-in:        How many OTHER clients also send to this beneficiary?
 *   2. Shared bene %: What fraction of this client's beneficiaries are shared with others?
 *   3. Network density: Among neighbor clients, how interconnected are they?
 *
 * At least 2 of 3 signals must exceed their thresholds for the rule to trigger.
 * The composite score must also exceed muleCompositeThreshold.
 */
@Component
public class MuleNetworkEvaluator implements RuleEvaluator {

    private final RiskThresholdConfig config;
    private final BeneficiaryGraphService graphService;

    public MuleNetworkEvaluator(RiskThresholdConfig config, BeneficiaryGraphService graphService) {
        this.config = config;
        this.graphService = graphService;
    }

    @Override
    public RuleType getSupportedRuleType() {
        return RuleType.MULE_NETWORK;
    }

    @Override
    public RuleResult evaluate(Transaction txn, ClientProfile profile, AnomalyRule rule, EvaluationContext context) {
        String beneKey = context.getCurrentBeneficiaryKey();
        if (beneKey == null || beneKey.isBlank()) {
            return notTriggered(rule, "No beneficiary data present");
        }
        if (!graphService.isGraphReady()) {
            return notTriggered(rule, "Beneficiary graph not yet initialized");
        }

        // Extract thresholds
        int minFanIn = (int) rule.getParamAsLong("minFanIn",
                config.getRuleDefaults().getMuleMinFanIn());
        double sharedBenePctThreshold = rule.getParamAsDouble("sharedBenePctThreshold",
                config.getRuleDefaults().getMuleSharedBenePct());
        double densityThreshold = rule.getParamAsDouble("densityThreshold",
                config.getRuleDefaults().getMuleDensityThreshold());

        // Signal weights
        double fanInWeight = rule.getParamAsDouble("fanInWeight", 0.4);
        double sharedWeight = rule.getParamAsDouble("sharedWeight", 0.35);
        double densityWeight = rule.getParamAsDouble("densityWeight", 0.25);

        // --- Signal 1: Fan-in ---
        int fanInCount = graphService.getFanInCount(beneKey);
        int otherSenders = Math.max(0, fanInCount - 1);
        double fanInScore = 0.0;
        if (otherSenders >= minFanIn) {
            fanInScore = Math.min(100.0,
                    ((double) (otherSenders - minFanIn) / Math.max(1, minFanIn * 2)) * 100.0);
            fanInScore = Math.max(fanInScore, 30.0);
        }

        // --- Signal 2: Shared beneficiary ratio ---
        int totalBene = graphService.getTotalBeneficiaryCount(txn.getClientId());
        int sharedBene = graphService.getSharedBeneficiaryCount(txn.getClientId());
        double sharedRatio = totalBene > 0 ? (double) sharedBene / totalBene : 0.0;
        double sharedScore = 0.0;
        if (sharedRatio * 100.0 >= sharedBenePctThreshold) {
            sharedScore = Math.min(100.0,
                    ((sharedRatio * 100.0 - sharedBenePctThreshold)
                            / Math.max(1.0, sharedBenePctThreshold)) * 100.0);
            sharedScore = Math.max(sharedScore, 30.0);
        }

        // --- Signal 3: Network density ---
        double density = graphService.getNetworkDensity(txn.getClientId());
        double densityScore = 0.0;
        if (density >= densityThreshold) {
            densityScore = Math.min(100.0,
                    ((density - densityThreshold) / Math.max(0.01, 1.0 - densityThreshold)) * 100.0);
            densityScore = Math.max(densityScore, 30.0);
        }

        // --- Combine: require at least 2 of 3 active ---
        int activeSignals = (fanInScore > 0 ? 1 : 0)
                + (sharedScore > 0 ? 1 : 0)
                + (densityScore > 0 ? 1 : 0);

        if (activeSignals < 2) {
            return notTriggered(rule, String.format(
                    "Mule signals below threshold: fanIn=%d (others=%d, min=%d), "
                            + "shared=%.1f%% (threshold=%.1f%%), density=%.3f (threshold=%.3f). "
                            + "Active: %d/3 (need >=2)",
                    fanInCount, otherSenders, minFanIn,
                    sharedRatio * 100, sharedBenePctThreshold,
                    density, densityThreshold, activeSignals));
        }

        double compositeScore = fanInScore * fanInWeight
                + sharedScore * sharedWeight
                + densityScore * densityWeight;
        double partialScore = Math.min(100.0, compositeScore);

        double triggerThreshold = rule.getVariancePct() > 0
                ? rule.getVariancePct()
                : config.getRuleDefaults().getMuleCompositeThreshold();

        if (partialScore < triggerThreshold) {
            return notTriggered(rule, String.format(
                    "Mule composite=%.1f below threshold=%.1f. "
                            + "FanIn=%d(%.1f), shared=%.1f%%(%.1f), density=%.3f(%.1f)",
                    partialScore, triggerThreshold,
                    fanInCount, fanInScore, sharedRatio * 100, sharedScore, density, densityScore));
        }

        // Build detailed reason
        Set<String> otherSenderIds = graphService.getOtherSenders(beneKey, txn.getClientId());
        String senderList = otherSenderIds.size() <= 5
                ? otherSenderIds.toString()
                : otherSenderIds.stream().limit(5).collect(Collectors.toList()) + "...+" + (otherSenderIds.size() - 5);

        String reason = String.format(
                "Mule network: bene %s has %d senders %s. "
                        + "Client shares %.1f%% of beneficiaries (%d/%d). "
                        + "Network density=%.3f. Score=%.1f "
                        + "[fanIn=%.1f*%.0f%% + shared=%.1f*%.0f%% + density=%.1f*%.0f%%]",
                beneKey, fanInCount, senderList,
                sharedRatio * 100, sharedBene, totalBene,
                density, partialScore,
                fanInScore, fanInWeight * 100,
                sharedScore, sharedWeight * 100,
                densityScore, densityWeight * 100);

        return RuleResult.builder()
                .ruleId(rule.getRuleId())
                .ruleName(rule.getName())
                .ruleType(rule.getRuleType())
                .triggered(true)
                .deviationPct(partialScore)
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
