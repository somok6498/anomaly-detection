package com.bank.anomaly.repository;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.ScanCallback;
import com.aerospike.client.policy.Policy;
import com.aerospike.client.policy.ScanPolicy;
import com.aerospike.client.policy.WritePolicy;
import com.bank.anomaly.config.AerospikeConfig;
import com.bank.anomaly.model.AnomalyRule;
import com.bank.anomaly.model.RuleType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Repository
public class RuleRepository {

    private static final Logger log = LoggerFactory.getLogger(RuleRepository.class);

    private final AerospikeClient client;
    private final String namespace;
    private final WritePolicy writePolicy;
    private final Policy readPolicy;
    private final ObjectMapper objectMapper;

    // In-memory cache of active rules, refreshed periodically
    private final AtomicReference<List<AnomalyRule>> cachedRules = new AtomicReference<>(new CopyOnWriteArrayList<>());

    public RuleRepository(AerospikeClient client,
                          @Qualifier("aerospikeNamespace") String namespace,
                          @Qualifier("defaultWritePolicy") WritePolicy writePolicy,
                          @Qualifier("defaultReadPolicy") Policy readPolicy) {
        this.client = client;
        this.namespace = namespace;
        this.writePolicy = writePolicy;
        this.readPolicy = readPolicy;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Start periodic cache refresh. Called from a @PostConstruct or config bean.
     */
    public void startCacheRefresh(int intervalSeconds) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rule-cache-refresh");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::refreshCache, 0, intervalSeconds, TimeUnit.SECONDS);
    }

    public void refreshCache() {
        try {
            List<AnomalyRule> allRules = scanAllRules();
            cachedRules.set(new CopyOnWriteArrayList<>(allRules));
            log.debug("Rule cache refreshed, {} rules loaded", allRules.size());
        } catch (Exception e) {
            log.error("Failed to refresh rule cache", e);
        }
    }

    /**
     * Get all active (enabled) rules from the in-memory cache.
     */
    public List<AnomalyRule> getActiveRules() {
        return cachedRules.get().stream()
                .filter(AnomalyRule::isEnabled)
                .toList();
    }

    /**
     * Get all rules (including disabled) from the in-memory cache.
     */
    public List<AnomalyRule> getAllRulesCached() {
        return cachedRules.get();
    }

    public List<AnomalyRule> findAll() {
        return scanAllRules();
    }

    public AnomalyRule findById(String ruleId) {
        Key key = new Key(namespace, AerospikeConfig.SET_ANOMALY_RULES, ruleId);
        Record record = client.get(readPolicy, key);
        if (record == null) return null;
        return mapRecordToRule(ruleId, record);
    }

    public void save(AnomalyRule rule) {
        Key key = new Key(namespace, AerospikeConfig.SET_ANOMALY_RULES, rule.getRuleId());

        Bin ruleIdBin = new Bin("ruleId", rule.getRuleId());
        Bin nameBin = new Bin("name", rule.getName());
        Bin descBin = new Bin("description", rule.getDescription());
        Bin ruleTypeBin = new Bin("ruleType", rule.getRuleType().name());
        Bin variancePctBin = new Bin("variancePct", rule.getVariancePct());
        Bin riskWeightBin = new Bin("riskWeight", rule.getRiskWeight());
        Bin enabledBin = new Bin("enabled", rule.isEnabled());
        Bin paramsBin = new Bin("params", serializeParams(rule.getParams()));

        client.put(writePolicy, key,
                ruleIdBin, nameBin, descBin, ruleTypeBin,
                variancePctBin, riskWeightBin, enabledBin, paramsBin);

        // Immediately refresh cache after save
        refreshCache();
    }

    public boolean delete(String ruleId) {
        Key key = new Key(namespace, AerospikeConfig.SET_ANOMALY_RULES, ruleId);
        boolean deleted = client.delete(writePolicy, key);
        if (deleted) {
            refreshCache();
        }
        return deleted;
    }

    /**
     * Scan all rules from Aerospike. Used for cache refresh and listing.
     */
    private List<AnomalyRule> scanAllRules() {
        List<AnomalyRule> rules = new ArrayList<>();
        ScanPolicy scanPolicy = new ScanPolicy();
        scanPolicy.concurrentNodes = true;
        scanPolicy.includeBinData = true;

        client.scanAll(scanPolicy, namespace, AerospikeConfig.SET_ANOMALY_RULES,
                (key, record) -> {
                    try {
                        String ruleId = record.getString("ruleId");
                        if (ruleId != null) {
                            rules.add(mapRecordToRule(ruleId, record));
                        }
                    } catch (Exception e) {
                        log.warn("Failed to deserialize rule record: {}", e.getMessage());
                    }
                });
        return rules;
    }

    private AnomalyRule mapRecordToRule(String ruleId, Record record) {
        return AnomalyRule.builder()
                .ruleId(ruleId)
                .name(record.getString("name"))
                .description(record.getString("description"))
                .ruleType(RuleType.valueOf(record.getString("ruleType")))
                .variancePct(record.getDouble("variancePct"))
                .riskWeight(record.getDouble("riskWeight"))
                .enabled(record.getBoolean("enabled"))
                .params(deserializeParams(record.getString("params")))
                .build();
    }

    private String serializeParams(Map<String, String> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, String> deserializeParams(String json) {
        if (json == null || json.isEmpty()) return new HashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
}
