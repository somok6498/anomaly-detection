package com.bank.anomaly.service;

import com.bank.anomaly.config.RiskThresholdConfig;
import com.bank.anomaly.model.AnomalyRule;
import com.bank.anomaly.repository.RuleRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Service layer for managing anomaly rules.
 * Handles CRUD operations and initializes the rule cache on startup.
 */
@Service
public class RuleService {

    private final RuleRepository ruleRepository;
    private final RiskThresholdConfig thresholdConfig;

    public RuleService(RuleRepository ruleRepository, RiskThresholdConfig thresholdConfig) {
        this.ruleRepository = ruleRepository;
        this.thresholdConfig = thresholdConfig;
    }

    @PostConstruct
    public void init() {
        // Start the periodic rule cache refresh
        ruleRepository.startCacheRefresh(thresholdConfig.getRuleCacheRefreshSeconds());
    }

    public List<AnomalyRule> getAllRules() {
        return ruleRepository.getAllRulesCached();
    }

    public AnomalyRule getRule(String ruleId) {
        return ruleRepository.findById(ruleId);
    }

    public AnomalyRule createRule(AnomalyRule rule) {
        if (rule.getRuleId() == null || rule.getRuleId().isEmpty()) {
            rule.setRuleId(UUID.randomUUID().toString());
        }
        ruleRepository.save(rule);
        return rule;
    }

    public AnomalyRule updateRule(String ruleId, AnomalyRule updated) {
        AnomalyRule existing = ruleRepository.findById(ruleId);
        if (existing == null) {
            return null;
        }

        // Update mutable fields
        if (updated.getName() != null) existing.setName(updated.getName());
        if (updated.getDescription() != null) existing.setDescription(updated.getDescription());
        if (updated.getRuleType() != null) existing.setRuleType(updated.getRuleType());
        existing.setVariancePct(updated.getVariancePct());
        existing.setRiskWeight(updated.getRiskWeight());
        existing.setEnabled(updated.isEnabled());
        if (updated.getParams() != null) existing.setParams(updated.getParams());

        ruleRepository.save(existing);
        return existing;
    }

    public boolean deleteRule(String ruleId) {
        return ruleRepository.delete(ruleId);
    }
}
