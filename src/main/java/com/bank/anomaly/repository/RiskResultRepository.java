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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

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

        client.put(writePolicy, key,
                txnIdBin, clientIdBin, scoreBin, riskLevelBin,
                actionBin, evaluatedAtBin, ruleResultsBin);
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
                .build();
    }

    public List<EvaluationResult> findByClientId(String clientId, int limit) {
        List<EvaluationResult> results = new ArrayList<>();
        ScanPolicy scanPolicy = new ScanPolicy();
        scanPolicy.concurrentNodes = true;

        client.scanAll(scanPolicy, namespace, AerospikeConfig.SET_RISK_RESULTS,
                (key, record) -> {
                    String recClientId = record.getString("clientId");
                    if (clientId.equals(recClientId)) {
                        synchronized (results) {
                            results.add(mapRecord(record));
                        }
                    }
                });

        results.sort(Comparator.comparingLong(EvaluationResult::getEvaluatedAt).reversed());
        if (results.size() > limit) {
            return results.subList(0, limit);
        }
        return results;
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
                .build();
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
