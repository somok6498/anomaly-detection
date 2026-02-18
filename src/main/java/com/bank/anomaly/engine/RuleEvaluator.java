package com.bank.anomaly.engine;

import com.bank.anomaly.model.AnomalyRule;
import com.bank.anomaly.model.ClientProfile;
import com.bank.anomaly.model.RuleResult;
import com.bank.anomaly.model.RuleType;
import com.bank.anomaly.model.Transaction;

/**
 * Interface for all anomaly rule evaluators.
 * Each implementation handles a specific RuleType.
 */
public interface RuleEvaluator {

    /**
     * The rule type this evaluator handles.
     */
    RuleType getSupportedRuleType();

    /**
     * Evaluate a transaction against a rule using the client's behavioral profile.
     *
     * @param txn     the incoming transaction
     * @param profile the client's historical behavioral profile
     * @param rule    the rule configuration (variance %, weight, params)
     * @param context evaluation context with additional data (e.g., current hourly count)
     * @return the evaluation result for this rule
     */
    RuleResult evaluate(Transaction txn, ClientProfile profile, AnomalyRule rule, EvaluationContext context);
}
