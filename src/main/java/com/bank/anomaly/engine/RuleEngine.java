package com.bank.anomaly.engine;

import com.bank.anomaly.config.MetricsConfig;
import com.bank.anomaly.model.AnomalyRule;
import com.bank.anomaly.model.ClientProfile;
import com.bank.anomaly.model.RuleResult;
import com.bank.anomaly.model.RuleType;
import com.bank.anomaly.model.Transaction;
import com.bank.anomaly.repository.RuleRepository;
import io.micrometer.observation.annotation.Observed;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.Span;
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
    private final Tracer tracer;
    private final MetricsConfig metricsConfig;

    public RuleEngine(RuleRepository ruleRepository, List<RuleEvaluator> evaluators,
                      Tracer tracer, MetricsConfig metricsConfig) {
        this.ruleRepository = ruleRepository;
        this.evaluatorMap = new EnumMap<>(RuleType.class);
        this.tracer = tracer;
        this.metricsConfig = metricsConfig;

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
    @Observed(name = "rules.evaluate_all", contextualName = "evaluate-all-rules")
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

            Span ruleSpan = tracer.nextSpan()
                    .name("rule.evaluate." + rule.getRuleType())
                    .tag("rule.id", rule.getRuleId())
                    .tag("rule.name", rule.getName())
                    .tag("rule.type", rule.getRuleType().name())
                    .start();

            try (Tracer.SpanInScope ws = tracer.withSpan(ruleSpan)) {
                RuleResult result = evaluator.evaluate(txn, profile, rule, context);
                results.add(result);

                ruleSpan.tag("rule.triggered", String.valueOf(result.isTriggered()));
                ruleSpan.tag("rule.score", String.valueOf(result.getPartialScore()));

                if (result.isTriggered()) {
                    metricsConfig.recordRuleTriggered(rule.getRuleType().name());
                    log.debug("Rule triggered: {} for client {} txn {} — score={}, reason={}",
                            rule.getName(), txn.getClientId(), txn.getTxnId(),
                            result.getPartialScore(), result.getReason());
                }
            } catch (Exception e) {
                ruleSpan.error(e);
                log.error("Error evaluating rule {} for txn {}: {}",
                        rule.getRuleId(), txn.getTxnId(), e.getMessage(), e);
                // Don't let one bad rule block the entire evaluation
            } finally {
                ruleSpan.end();
            }
        }

        return results;
    }
}
