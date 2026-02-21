package com.bank.anomaly.engine.evaluators;

import com.bank.anomaly.engine.EvaluationContext;
import com.bank.anomaly.engine.RuleEvaluator;
import com.bank.anomaly.engine.isolationforest.FeatureExtractor;
import com.bank.anomaly.engine.isolationforest.IsolationForest;
import com.bank.anomaly.model.AnomalyRule;
import com.bank.anomaly.model.ClientProfile;
import com.bank.anomaly.model.RuleResult;
import com.bank.anomaly.model.RuleType;
import com.bank.anomaly.model.Transaction;
import com.bank.anomaly.repository.IsolationForestModelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Evaluates transactions using a per-client Isolation Forest model.
 *
 * The IF model catches multi-dimensional anomalies — where each feature
 * is individually borderline-normal but the combination is rare.
 * This complements the rule-based evaluators which check single dimensions.
 *
 * Scoring:
 *   IF anomaly score ranges from 0.0 (normal) to 1.0 (anomalous).
 *   Score > 0.5 indicates anomaly; ~0.5 is uncertain; < 0.5 is normal.
 *   The variancePct on the rule is used as the anomaly threshold (0-100 scale,
 *   mapped to 0.0-1.0). Default: 60 → threshold = 0.60.
 */
@Component
public class IsolationForestEvaluator implements RuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(IsolationForestEvaluator.class);

    private final IsolationForestModelRepository modelRepository;

    public IsolationForestEvaluator(IsolationForestModelRepository modelRepository) {
        this.modelRepository = modelRepository;
    }

    @Override
    public RuleType getSupportedRuleType() {
        return RuleType.ISOLATION_FOREST;
    }

    @Override
    public RuleResult evaluate(Transaction txn, ClientProfile profile, AnomalyRule rule, EvaluationContext context) {
        // Load trained model for this client
        IsolationForest forest = modelRepository.load(txn.getClientId());
        if (forest == null) {
            return notTriggered(rule, "No IF model trained for this client");
        }

        // Extract feature vector
        double[] features = FeatureExtractor.extract(txn, profile, context);

        // Compute anomaly score (0.0 to 1.0)
        double anomalyScore = forest.anomalyScore(features);

        // Threshold from rule config (variancePct as percentage, e.g. 60 → 0.60)
        double threshold = rule.getVariancePct() / 100.0;

        if (anomalyScore <= threshold) {
            return notTriggered(rule,
                    String.format("IF score=%.3f (threshold=%.2f). Within normal range.", anomalyScore, threshold));
        }

        // Compute feature contributions for explainability
        double[] featureMeans = computeFeatureMeans(profile);
        double[] contributions = forest.featureContributions(features, featureMeans);

        // Build reason string highlighting top contributing features
        String reason = buildReason(anomalyScore, threshold, features, contributions);

        // Map anomaly score to partial score (0-100)
        // Score scales from 0 at threshold to 100 at score=1.0
        double excess = anomalyScore - threshold;
        double range = 1.0 - threshold;
        double partialScore = range > 0 ? Math.min(100.0, (excess / range) * 100.0) : 100.0;

        double deviationPct = (anomalyScore / threshold - 1.0) * 100.0;

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

    private double[] computeFeatureMeans(ClientProfile profile) {
        double[] means = new double[FeatureExtractor.FEATURE_COUNT];
        // For a "normal" point, Z-scores are ~0, frequency is ~0 (inverted),
        // ratios are ~1.0, hour is ~0.5 (midday)
        means[0] = 0.0;  // Amount Z-score mean
        means[1] = 0.3;  // Type frequency (inverted) — typical client uses a few types
        means[2] = 1.0;  // TPS ratio baseline
        means[3] = 1.0;  // Hourly amount ratio baseline
        means[4] = 0.0;  // Type amount Z-score mean
        means[5] = 0.5;  // Hour-of-day midpoint
        means[6] = 1.0;  // Beneficiary concentration baseline (ratio=1.0 means uniform)
        means[7] = 0.1;  // Beneficiary window velocity baseline (low)
        return means;
    }

    private String buildReason(double anomalyScore, double threshold,
                               double[] features, double[] contributions) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Isolation Forest: score=%.3f (threshold=%.2f). ", anomalyScore, threshold));
        sb.append("Multi-dimensional anomaly detected. Top factors: ");

        // Find top 3 contributing features
        int[] topIndices = topN(contributions, 3);
        for (int k = 0; k < topIndices.length; k++) {
            int idx = topIndices[k];
            if (contributions[idx] <= 0) break;
            if (k > 0) sb.append(", ");
            sb.append(String.format("%s=%.2f (contribution=%.3f)",
                    FeatureExtractor.FEATURE_NAMES[idx], features[idx], contributions[idx]));
        }

        return sb.toString();
    }

    private int[] topN(double[] values, int n) {
        int[] indices = new int[Math.min(n, values.length)];
        double[] topVals = new double[indices.length];
        java.util.Arrays.fill(topVals, -1);

        for (int i = 0; i < values.length; i++) {
            for (int j = 0; j < indices.length; j++) {
                if (values[i] > topVals[j]) {
                    // Shift down
                    for (int k = indices.length - 1; k > j; k--) {
                        indices[k] = indices[k - 1];
                        topVals[k] = topVals[k - 1];
                    }
                    indices[j] = i;
                    topVals[j] = values[i];
                    break;
                }
            }
        }
        return indices;
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
