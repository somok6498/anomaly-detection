package com.bank.anomaly.repository;

import com.bank.anomaly.model.AnomalyRule;
import com.bank.anomaly.model.RuleType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

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

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicReference<List<AnomalyRule>> cachedRules = new AtomicReference<>(new CopyOnWriteArrayList<>());

    private final RowMapper<AnomalyRule> rowMapper = (rs, rowNum) ->
            AnomalyRule.builder()
                    .ruleId(rs.getString("rule_id"))
                    .name(rs.getString("rule_name"))
                    .description(rs.getString("description"))
                    .ruleType(RuleType.valueOf(rs.getString("rule_type")))
                    .variancePct(rs.getDouble("variance_pct"))
                    .riskWeight(rs.getDouble("risk_weight"))
                    .enabled(rs.getInt("enabled") == 1)
                    .params(deserializeParams(rs.getString("params")))
                    .build();

    public RuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

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
            List<AnomalyRule> allRules = findAll();
            cachedRules.set(new CopyOnWriteArrayList<>(allRules));
            log.debug("Rule cache refreshed, {} rules loaded", allRules.size());
        } catch (Exception e) {
            log.error("Failed to refresh rule cache", e);
        }
    }

    public List<AnomalyRule> getActiveRules() {
        return cachedRules.get().stream()
                .filter(AnomalyRule::isEnabled)
                .toList();
    }

    public List<AnomalyRule> getAllRulesCached() {
        return cachedRules.get();
    }

    public List<AnomalyRule> findAll() {
        return jdbc.query("SELECT * FROM anomaly_rules", rowMapper);
    }

    public AnomalyRule findById(String ruleId) {
        List<AnomalyRule> results = jdbc.query(
                "SELECT * FROM anomaly_rules WHERE rule_id = ?", rowMapper, ruleId);
        return results.isEmpty() ? null : results.get(0);
    }

    public void save(AnomalyRule rule) {
        jdbc.update("""
                MERGE INTO anomaly_rules r
                USING (SELECT ? AS rule_id FROM dual) s ON (r.rule_id = s.rule_id)
                WHEN MATCHED THEN UPDATE SET
                    rule_name = ?, description = ?, rule_type = ?,
                    variance_pct = ?, risk_weight = ?, enabled = ?,
                    params = ?, version = version + 1
                WHEN NOT MATCHED THEN INSERT (
                    rule_id, rule_name, description, rule_type,
                    variance_pct, risk_weight, enabled, params, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
                """,
                rule.getRuleId(),
                rule.getName(), rule.getDescription(), rule.getRuleType().name(),
                rule.getVariancePct(), rule.getRiskWeight(), rule.isEnabled() ? 1 : 0,
                serializeParams(rule.getParams()),
                rule.getRuleId(), rule.getName(), rule.getDescription(), rule.getRuleType().name(),
                rule.getVariancePct(), rule.getRiskWeight(), rule.isEnabled() ? 1 : 0,
                serializeParams(rule.getParams()));

        refreshCache();
    }

    public boolean delete(String ruleId) {
        int deleted = jdbc.update("DELETE FROM anomaly_rules WHERE rule_id = ?", ruleId);
        if (deleted > 0) {
            refreshCache();
        }
        return deleted > 0;
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
