package com.bank.anomaly.engine.evaluators;

import com.bank.anomaly.engine.EvaluationContext;
import com.bank.anomaly.engine.RuleEvaluator;
import com.bank.anomaly.model.*;
import com.bank.anomaly.repository.RiskResultRepository;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Meta-rule that detects when specific rules are triggered repeatedly
 * for the same client within a configurable time window.
 *
 * Example: "CLIENT-006 triggered AMOUNT_ANOMALY 3 times in the last 1 hour"
 *
 * Config params:
 *   lookbackHours    — window size in hours (default: 1)
 *   triggerThreshold — min trigger count to flag (default: 3)
 *   monitoredRules   — comma-separated rule IDs or "ALL" (default: ALL)
 */
@Component
public class TemporalRuleCorrelationEvaluator implements RuleEvaluator {

    private final RiskResultRepository riskResultRepository;

    public TemporalRuleCorrelationEvaluator(RiskResultRepository riskResultRepository) {
        this.riskResultRepository = riskResultRepository;
    }

    @Override
    public RuleType getSupportedRuleType() {
        return RuleType.TEMPORAL_RULE_CORRELATION;
    }

    @Override
    public RuleResult evaluate(Transaction txn, ClientProfile profile,
                                AnomalyRule rule, EvaluationContext context) {

        double lookbackHours = rule.getParamAsDouble("lookbackHours", 1.0);
        int triggerThreshold = (int) rule.getParamAsLong("triggerThreshold", 3);
        String monitoredRulesStr = rule.getParams().getOrDefault("monitoredRules", "ALL");

        long nowMs = txn.getTimestamp();
        long windowStart = nowMs - (long) (lookbackHours * 3_600_000);

        // Fetch recent evaluation results for this client
        PagedResponse<EvaluationResult> recentPage =
                riskResultRepository.findByClientId(txn.getClientId(), 500, nowMs);

        // Filter to time window (exclude current txn if somehow already persisted)
        List<EvaluationResult> windowResults = recentPage.data().stream()
                .filter(r -> r.getEvaluatedAt() >= windowStart && r.getEvaluatedAt() < nowMs)
                .filter(r -> !r.getTxnId().equals(txn.getTxnId()))
                .toList();

        if (windowResults.isEmpty()) {
            return notTriggered(rule, lookbackHours);
        }

        // Parse monitored rules (empty set = all rules)
        Set<String> monitoredRuleIds = parseMonitoredRules(monitoredRulesStr);

        // Count triggers per rule ID
        Map<String, Integer> triggerCounts = new HashMap<>();
        Map<String, String> ruleNames = new HashMap<>();

        for (EvaluationResult evalResult : windowResults) {
            for (RuleResult rr : evalResult.getRuleResults()) {
                if (rr.isTriggered()) {
                    String ruleId = rr.getRuleId();
                    if (monitoredRuleIds.isEmpty() || monitoredRuleIds.contains(ruleId)) {
                        triggerCounts.merge(ruleId, 1, Integer::sum);
                        ruleNames.putIfAbsent(ruleId, rr.getRuleName());
                    }
                }
            }
        }

        // Find rules exceeding threshold
        List<String> exceededRules = triggerCounts.entrySet().stream()
                .filter(e -> e.getValue() >= triggerThreshold)
                .sorted(Comparator.comparingInt(e -> -e.getValue()))
                .map(Map.Entry::getKey)
                .toList();

        if (exceededRules.isEmpty()) {
            return notTriggered(rule, lookbackHours);
        }

        // Build reason and score
        StringBuilder reason = new StringBuilder();
        double totalExcessRatio = 0;

        for (String ruleId : exceededRules) {
            int count = triggerCounts.get(ruleId);
            String name = ruleNames.getOrDefault(ruleId, ruleId);
            reason.append(String.format("%s triggered %s %d times in the last %.0f hour(s). ",
                    txn.getClientId(), name, count, lookbackHours));
            totalExcessRatio += (double) count / triggerThreshold;
        }

        double partialScore = Math.min(100.0, totalExcessRatio * 25.0);
        double deviationPct = (totalExcessRatio - 1.0) * 100.0;

        return RuleResult.builder()
                .ruleId(rule.getRuleId())
                .ruleName(rule.getName())
                .ruleType(rule.getRuleType())
                .triggered(true)
                .deviationPct(deviationPct)
                .partialScore(partialScore)
                .riskWeight(rule.getRiskWeight())
                .reason(reason.toString().trim())
                .build();
    }

    private Set<String> parseMonitoredRules(String monitoredRulesStr) {
        if (monitoredRulesStr == null || monitoredRulesStr.equalsIgnoreCase("ALL")) {
            return Collections.emptySet();
        }
        return Arrays.stream(monitoredRulesStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    private RuleResult notTriggered(AnomalyRule rule, double lookbackHours) {
        return RuleResult.builder()
                .ruleId(rule.getRuleId())
                .ruleName(rule.getName())
                .ruleType(rule.getRuleType())
                .triggered(false)
                .deviationPct(0.0)
                .partialScore(0.0)
                .riskWeight(rule.getRiskWeight())
                .reason(String.format("No repeated rule triggers detected in the last %.0f hour(s)", lookbackHours))
                .build();
    }
}
