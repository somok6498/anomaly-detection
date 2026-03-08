package com.bank.anomaly.repository;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.policy.Policy;
import com.aerospike.client.policy.WritePolicy;
import com.bank.anomaly.config.AerospikeConfig;
import com.bank.anomaly.model.EvaluationResult;
import com.bank.anomaly.model.RiskLevel;
import com.bank.anomaly.model.RuleResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

import com.aerospike.client.policy.ScanPolicy;

import com.bank.anomaly.model.PagedResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class RiskResultRepository {

    private static final Logger log = LoggerFactory.getLogger(RiskResultRepository.class);

    private final AerospikeClient client;
    private final String namespace;
    private final WritePolicy writePolicy;
    private final Policy readPolicy;
    private final ObjectMapper objectMapper;

    public RiskResultRepository(AerospikeClient client,
                                @Qualifier("aerospikeNamespace") String namespace,
                                @Qualifier("defaultWritePolicy") WritePolicy writePolicy,
                                @Qualifier("defaultReadPolicy") Policy readPolicy) {
        this.client = client;
        this.namespace = namespace;
        this.writePolicy = writePolicy;
        this.readPolicy = readPolicy;
        this.objectMapper = new ObjectMapper();
    }

    public void save(EvaluationResult result) {
        Key key = new Key(namespace, AerospikeConfig.SET_RISK_RESULTS, result.getTxnId());

        Bin txnIdBin = new Bin("txnId", result.getTxnId());
        Bin clientIdBin = new Bin("clientId", result.getClientId());
        Bin scoreBin = new Bin("compositeScore", result.getCompositeScore());
        Bin riskLevelBin = new Bin("riskLevel", result.getRiskLevel().name());
        Bin actionBin = new Bin("action", result.getAction());
        Bin evaluatedAtBin = new Bin("evaluatedAt", result.getEvaluatedAt());
        Bin ruleResultsBin = new Bin("ruleResults", serializeRuleResults(result.getRuleResults()));
        Bin triggeredCountBin = new Bin("trigRuleCount", result.getTriggeredRuleCount());
        Bin breadthBonusBin = new Bin("breadthBonus", result.getBreadthBonus());
        Bin aiExplanationBin = new Bin("aiExplanation", result.getAiExplanation());
        Bin attackPatternBin = new Bin("atkPattern", result.getAttackPattern());

        client.put(writePolicy, key,
                txnIdBin, clientIdBin, scoreBin, riskLevelBin,
                actionBin, evaluatedAtBin, ruleResultsBin,
                triggeredCountBin, breadthBonusBin, aiExplanationBin, attackPatternBin);
    }

    public EvaluationResult findByTxnId(String txnId) {
        Key key = new Key(namespace, AerospikeConfig.SET_RISK_RESULTS, txnId);
        Record record = client.get(readPolicy, key);
        if (record == null) return null;

        return EvaluationResult.builder()
                .txnId(txnId)
                .clientId(record.getString("clientId"))
                .compositeScore(record.getDouble("compositeScore"))
                .riskLevel(RiskLevel.valueOf(record.getString("riskLevel")))
                .action(record.getString("action"))
                .evaluatedAt(record.getLong("evaluatedAt"))
                .ruleResults(deserializeRuleResults(record.getString("ruleResults")))
                .triggeredRuleCount(safeInt(record, "trigRuleCount"))
                .breadthBonus(safeDouble(record, "breadthBonus"))
                .aiExplanation(record.getString("aiExplanation"))
                .attackPattern(record.getString("atkPattern"))
                .build();
    }

    public PagedResponse<EvaluationResult> findByClientId(String clientId, int limit, Long before) {
        List<EvaluationResult> results = new ArrayList<>();
        ScanPolicy scanPolicy = new ScanPolicy();
        scanPolicy.concurrentNodes = true;

        client.scanAll(scanPolicy, namespace, AerospikeConfig.SET_RISK_RESULTS,
                (key, record) -> {
                    String recClientId = record.getString("clientId");
                    if (clientId.equals(recClientId)) {
                        if (before != null && record.getLong("evaluatedAt") >= before) return;
                        synchronized (results) {
                            results.add(mapRecord(record));
                        }
                    }
                });

        results.sort(Comparator.comparingLong(EvaluationResult::getEvaluatedAt).reversed());
        boolean hasMore = results.size() > limit;
        List<EvaluationResult> page = hasMore ? new ArrayList<>(results.subList(0, limit)) : results;
        String nextCursor = hasMore ? String.valueOf(page.get(page.size() - 1).getEvaluatedAt()) : null;
        return new PagedResponse<>(page, hasMore, nextCursor);
    }

    public List<EvaluationResult> findByTimeRange(long fromMs, long toMs,
                                                   String riskLevel, String action, int maxResults) {
        List<EvaluationResult> results = new ArrayList<>();
        ScanPolicy scanPolicy = new ScanPolicy();
        scanPolicy.concurrentNodes = true;

        client.scanAll(scanPolicy, namespace, AerospikeConfig.SET_RISK_RESULTS,
                (key, record) -> {
                    try {
                        long ts = record.getLong("evaluatedAt");
                        if (ts < fromMs || ts > toMs) return;
                        if (riskLevel != null && !riskLevel.equalsIgnoreCase(record.getString("riskLevel"))) return;
                        if (action != null && !action.equalsIgnoreCase(record.getString("action"))) return;
                        synchronized (results) {
                            if (results.size() < maxResults) {
                                results.add(mapRecord(record));
                            }
                        }
                    } catch (Exception ignored) {}
                });

        return results;
    }

    public long countDistinctClientsByTimeRange(long fromMs, long toMs, String riskLevel, String action) {
        Set<String> clientIds = ConcurrentHashMap.newKeySet();
        ScanPolicy scanPolicy = new ScanPolicy();
        scanPolicy.concurrentNodes = true;

        client.scanAll(scanPolicy, namespace, AerospikeConfig.SET_RISK_RESULTS,
                (key, record) -> {
                    try {
                        long ts = record.getLong("evaluatedAt");
                        if (ts < fromMs || ts > toMs) return;
                        if (riskLevel != null && !riskLevel.equalsIgnoreCase(record.getString("riskLevel"))) return;
                        if (action != null && !action.equalsIgnoreCase(record.getString("action"))) return;
                        clientIds.add(record.getString("clientId"));
                    } catch (Exception ignored) {}
                });

        return clientIds.size();
    }

    private EvaluationResult mapRecord(Record record) {
        return EvaluationResult.builder()
                .txnId(record.getString("txnId"))
                .clientId(record.getString("clientId"))
                .compositeScore(record.getDouble("compositeScore"))
                .riskLevel(RiskLevel.valueOf(record.getString("riskLevel")))
                .action(record.getString("action"))
                .evaluatedAt(record.getLong("evaluatedAt"))
                .ruleResults(deserializeRuleResults(record.getString("ruleResults")))
                .triggeredRuleCount(safeInt(record, "trigRuleCount"))
                .breadthBonus(safeDouble(record, "breadthBonus"))
                .aiExplanation(record.getString("aiExplanation"))
                .attackPattern(record.getString("atkPattern"))
                .build();
    }

    private int safeInt(Record record, String bin) {
        try { return record.getInt(bin); } catch (Exception e) { return 0; }
    }

    private double safeDouble(Record record, String bin) {
        try { return record.getDouble(bin); } catch (Exception e) { return 0.0; }
    }

    public void updateAiExplanation(String txnId, String aiExplanation) {
        Key key = new Key(namespace, AerospikeConfig.SET_RISK_RESULTS, txnId);
        client.put(writePolicy, key, new Bin("aiExplanation", aiExplanation));
    }

    public void updateAttackPattern(String txnId, String attackPattern) {
        Key key = new Key(namespace, AerospikeConfig.SET_RISK_RESULTS, txnId);
        client.put(writePolicy, key, new Bin("atkPattern", attackPattern));
    }

    private String serializeRuleResults(List<RuleResult> results) {
        try {
            return objectMapper.writeValueAsString(results);
        } catch (Exception e) {
            log.error("Failed to serialize rule results", e);
            return "[]";
        }
    }

    private List<RuleResult> deserializeRuleResults(String json) {
        if (json == null || json.isEmpty()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<RuleResult>>() {});
        } catch (Exception e) {
            log.error("Failed to deserialize rule results", e);
            return Collections.emptyList();
        }
    }
}
