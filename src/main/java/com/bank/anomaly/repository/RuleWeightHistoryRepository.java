package com.bank.anomaly.repository;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.policy.ScanPolicy;
import com.aerospike.client.policy.WritePolicy;
import com.bank.anomaly.config.AerospikeConfig;
import com.bank.anomaly.model.RuleWeightChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

import com.bank.anomaly.model.PagedResponse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Repository
public class RuleWeightHistoryRepository {

    private static final Logger log = LoggerFactory.getLogger(RuleWeightHistoryRepository.class);

    private final AerospikeClient client;
    private final String namespace;
    private final WritePolicy writePolicy;

    public RuleWeightHistoryRepository(AerospikeClient client,
                                        @Qualifier("aerospikeNamespace") String namespace,
                                        @Qualifier("defaultWritePolicy") WritePolicy writePolicy) {
        this.client = client;
        this.namespace = namespace;
        this.writePolicy = writePolicy;
    }

    public void save(RuleWeightChange change) {
        String recordKey = change.getRuleId() + "_" + change.getAdjustedAt();
        Key key = new Key(namespace, AerospikeConfig.SET_WEIGHT_HISTORY, recordKey);

        Bin ruleIdBin = new Bin("ruleId", change.getRuleId());
        Bin oldWeightBin = new Bin("oldWeight", change.getOldWeight());
        Bin newWeightBin = new Bin("newWeight", change.getNewWeight());
        Bin tpCountBin = new Bin("tpCount", change.getTpCount());
        Bin fpCountBin = new Bin("fpCount", change.getFpCount());
        Bin tpFpRatioBin = new Bin("tpFpRatio", change.getTpFpRatio());
        Bin adjustedAtBin = new Bin("adjustedAt", change.getAdjustedAt());

        client.put(writePolicy, key,
                ruleIdBin, oldWeightBin, newWeightBin,
                tpCountBin, fpCountBin, tpFpRatioBin, adjustedAtBin);
    }

    public PagedResponse<RuleWeightChange> findByRuleId(String ruleId, int limit, Long before) {
        List<RuleWeightChange> results = new ArrayList<>();
        ScanPolicy scanPolicy = new ScanPolicy();
        scanPolicy.concurrentNodes = true;

        client.scanAll(scanPolicy, namespace, AerospikeConfig.SET_WEIGHT_HISTORY,
                (key, record) -> {
                    try {
                        String recRuleId = record.getString("ruleId");
                        if (ruleId.equals(recRuleId)) {
                            if (before != null && record.getLong("adjustedAt") >= before) return;
                            synchronized (results) {
                                results.add(mapRecord(record));
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Failed to read weight history record: {}", e.getMessage());
                    }
                });

        results.sort(Comparator.comparingLong(RuleWeightChange::getAdjustedAt).reversed());
        boolean hasMore = results.size() > limit;
        List<RuleWeightChange> page = hasMore ? new ArrayList<>(results.subList(0, limit)) : results;
        String nextCursor = hasMore ? String.valueOf(page.get(page.size() - 1).getAdjustedAt()) : null;
        return new PagedResponse<>(page, hasMore, nextCursor);
    }

    public PagedResponse<RuleWeightChange> findAll(int limit, Long before) {
        List<RuleWeightChange> results = new ArrayList<>();
        ScanPolicy scanPolicy = new ScanPolicy();
        scanPolicy.concurrentNodes = true;

        client.scanAll(scanPolicy, namespace, AerospikeConfig.SET_WEIGHT_HISTORY,
                (key, record) -> {
                    try {
                        if (before != null && record.getLong("adjustedAt") >= before) return;
                        synchronized (results) {
                            results.add(mapRecord(record));
                        }
                    } catch (Exception e) {
                        log.warn("Failed to read weight history record: {}", e.getMessage());
                    }
                });

        results.sort(Comparator.comparingLong(RuleWeightChange::getAdjustedAt).reversed());
        boolean hasMore = results.size() > limit;
        List<RuleWeightChange> page = hasMore ? new ArrayList<>(results.subList(0, limit)) : results;
        String nextCursor = hasMore ? String.valueOf(page.get(page.size() - 1).getAdjustedAt()) : null;
        return new PagedResponse<>(page, hasMore, nextCursor);
    }

    private RuleWeightChange mapRecord(Record record) {
        return RuleWeightChange.builder()
                .ruleId(record.getString("ruleId"))
                .oldWeight(record.getDouble("oldWeight"))
                .newWeight(record.getDouble("newWeight"))
                .tpCount(record.getInt("tpCount"))
                .fpCount(record.getInt("fpCount"))
                .tpFpRatio(record.getDouble("tpFpRatio"))
                .adjustedAt(record.getLong("adjustedAt"))
                .build();
    }
}
