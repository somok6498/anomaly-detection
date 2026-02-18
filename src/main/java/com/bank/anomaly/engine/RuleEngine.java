package com.bank.anomaly.engine;

import com.bank.anomaly.model.AnomalyRule;
import com.bank.anomaly.model.ClientProfile;
import com.bank.anomaly.model.RuleResult;
import com.bank.anomaly.model.RuleType;
import com.bank.anomaly.model.Transaction;
import com.bank.anomaly.repository.RuleRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Core rule engine that evaluates all active rules against a transaction.
 * Uses the Strategy pattern: each RuleType is handled by a registered RuleEvaluator.
 */
@Component
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final RuleRepository ruleRepository;
    private final Map<RuleType, RuleEvaluator> evaluatorMap;

    public RuleEngine(RuleRepository ruleRepository, List<RuleEvaluator> evaluators) {
        this.ruleRepository = ruleRepository;
        this.evaluatorMap = new EnumMap<>(RuleType.class);

        // Auto-register all evaluator implementations
        for (RuleEvaluator evaluator : evaluators) {
            evaluatorMap.put(evaluator.getSupportedRuleType(), evaluator);
            log.info("Registered rule evaluator: {} -> {}",
                    evaluator.getSupportedRuleType(), evaluator.getClass().getSimpleName());
        }
    }

    /**
     * Evaluate all active rules against the given transaction.
     *
     * @param txn     the incoming transaction
     * @param profile the client's behavioral profile
     * @param context evaluation context with live counters
     * @return list of results from all evaluated rules
     */
    public List<RuleResult> evaluateAll(Transaction txn, ClientProfile profile, EvaluationContext context) {
        List<AnomalyRule> activeRules = ruleRepository.getActiveRules();
        List<RuleResult> results = new ArrayList<>();

        for (AnomalyRule rule : activeRules) {
            RuleEvaluator evaluator = evaluatorMap.get(rule.getRuleType());
            if (evaluator == null) {
                log.warn("No evaluator registered for rule type: {}, rule: {}",
                        rule.getRuleType(), rule.getRuleId());
                continue;
            }

            try {
                RuleResult result = evaluator.evaluate(txn, profile, rule, context);
                results.add(result);

                if (result.isTriggered()) {
                    log.debug("Rule triggered: {} for client {} txn {} — score={}, reason={}",
                            rule.getName(), txn.getClientId(), txn.getTxnId(),
                            result.getPartialScore(), result.getReason());
                }
            } catch (Exception e) {
                log.error("Error evaluating rule {} for txn {}: {}",
                        rule.getRuleId(), txn.getTxnId(), e.getMessage(), e);
                // Don't let one bad rule block the entire evaluation
            }
        }

        return results;
    }
}
