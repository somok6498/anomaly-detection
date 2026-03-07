package com.bank.anomaly.repository;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.policy.Policy;
import com.aerospike.client.policy.ScanPolicy;
import com.aerospike.client.policy.WritePolicy;
import com.bank.anomaly.config.AerospikeConfig;
import com.bank.anomaly.model.AiFeedback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Repository
public class AiFeedbackRepository {

    private final AerospikeClient client;
    private final String namespace;
    private final WritePolicy writePolicy;
    private final Policy readPolicy;

    public AiFeedbackRepository(AerospikeClient client,
                                @Qualifier("aerospikeNamespace") String namespace,
                                @Qualifier("defaultWritePolicy") WritePolicy writePolicy,
                                @Qualifier("defaultReadPolicy") Policy readPolicy) {
        this.client = client;
        this.namespace = namespace;
        this.writePolicy = writePolicy;
        this.readPolicy = readPolicy;
    }

    public void save(AiFeedback feedback) {
        Key key = new Key(namespace, AerospikeConfig.SET_AI_FEEDBACK, feedback.getTxnId());
        client.put(writePolicy, key,
                new Bin("aiFbHelpful", feedback.isHelpful()),
                new Bin("aiFbOperator", feedback.getOperatorId()),
                new Bin("aiFbTimestamp", feedback.getTimestamp()));
    }

    public AiFeedback findByTxnId(String txnId) {
        Key key = new Key(namespace, AerospikeConfig.SET_AI_FEEDBACK, txnId);
        Record record = client.get(readPolicy, key);
        if (record == null) return null;

        return AiFeedback.builder()
                .txnId(txnId)
                .helpful(record.getBoolean("aiFbHelpful"))
                .operatorId(record.getString("aiFbOperator"))
                .timestamp(record.getLong("aiFbTimestamp"))
                .build();
    }

    public Map<String, Object> getStats() {
        AtomicInteger helpful = new AtomicInteger(0);
        AtomicInteger notHelpful = new AtomicInteger(0);

        ScanPolicy scanPolicy = new ScanPolicy();
        scanPolicy.concurrentNodes = true;

        client.scanAll(scanPolicy, namespace, AerospikeConfig.SET_AI_FEEDBACK,
                (key, record) -> {
                    if (record.getBoolean("aiFbHelpful")) {
                        helpful.incrementAndGet();
                    } else {
                        notHelpful.incrementAndGet();
                    }
                });

        int total = helpful.get() + notHelpful.get();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("helpful", helpful.get());
        stats.put("notHelpful", notHelpful.get());
        stats.put("total", total);
        stats.put("helpfulPct", total > 0 ? (helpful.get() * 100.0 / total) : 0.0);
        return stats;
    }
}
