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
 * Detects when a client uses a transaction type they rarely or never use.
 *
 * Logic: If the transaction type's historical frequency is below a minimum
 * threshold (derived from variancePct), the transaction is flagged.
 *
 * Example: Client has 95% NEFT, 5% RTGS, 0% IMPS. An IMPS transaction
 * would score very high because IMPS frequency (0%) is far below expected.
 *
 * Rule params:
 *   - "minTypeFrequencyPct" (default: from config)
 *     Transactions of a type making up less than this % of total are flagged.
 */
@Component
public class TransactionTypeEvaluator implements RuleEvaluator {

    private final RiskThresholdConfig config;

    public TransactionTypeEvaluator(RiskThresholdConfig config) {
        this.config = config;
    }

    @Override
    public RuleType getSupportedRuleType() {
        return RuleType.TRANSACTION_TYPE_ANOMALY;
    }

    @Override
    public RuleResult evaluate(Transaction txn, ClientProfile profile, AnomalyRule rule, EvaluationContext context) {
        // If client has no history, can't evaluate
        if (profile.getTotalTxnCount() == 0) {
            return notTriggered(rule);
        }

        double typeFrequency = profile.getTypeFrequency(txn.getTxnType());
        double typeFrequencyPct = typeFrequency * 100.0;

        // The threshold: transaction types with frequency below this % are considered anomalous
        // variancePct is used as the minimum expected frequency percentage
        double minFrequencyPct = rule.getParamAsDouble("minTypeFrequencyPct",
                config.getRuleDefaults().getMinTypeFrequencyPct());

        if (typeFrequencyPct >= minFrequencyPct) {
            return notTriggered(rule);
        }

        // Calculate score: how anomalous is this type for the client?
        // Score is proportional to how far below the threshold the type frequency is
        double deviationPct;
        double partialScore;

        if (typeFrequencyPct == 0.0) {
            // Never-seen type — maximum score
            deviationPct = 100.0;
            partialScore = 100.0;
        } else {
            // Proportional: score = (1 - frequency/threshold) * 100
            deviationPct = ((minFrequencyPct - typeFrequencyPct) / minFrequencyPct) * 100.0;
            partialScore = Math.min(100.0, deviationPct);
        }

        String reason = String.format(
                "Transaction type %s has only %.2f%% frequency (threshold: %.2f%%). " +
                        "Client has %d total transactions, %d of type %s.",
                txn.getTxnType(), typeFrequencyPct, minFrequencyPct,
                profile.getTotalTxnCount(),
                profile.getTxnTypeCounts().getOrDefault(txn.getTxnType(), 0L),
                txn.getTxnType());

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
                .reason("Transaction type is within normal range")
                .build();
    }
}
