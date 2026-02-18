package com.bank.anomaly.repository;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.policy.Policy;
import com.aerospike.client.policy.ScanPolicy;
import com.aerospike.client.policy.WritePolicy;
import com.bank.anomaly.config.AerospikeConfig;
import com.bank.anomaly.model.Transaction;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Repository
public class TransactionRepository {

    private final AerospikeClient client;
    private final String namespace;
    private final Policy readPolicy;
    private final WritePolicy writePolicy;

    public TransactionRepository(AerospikeClient client,
                                 @Qualifier("aerospikeNamespace") String namespace,
                                 @Qualifier("defaultReadPolicy") Policy readPolicy,
                                 @Qualifier("defaultWritePolicy") WritePolicy writePolicy) {
        this.client = client;
        this.namespace = namespace;
        this.readPolicy = readPolicy;
        this.writePolicy = writePolicy;
    }

    public void save(Transaction txn) {
        Key key = new Key(namespace, AerospikeConfig.SET_TRANSACTIONS, txn.getTxnId());
        client.put(writePolicy, key,
                new Bin("txnId", txn.getTxnId()),
                new Bin("clientId", txn.getClientId()),
                new Bin("txnType", txn.getTxnType()),
                new Bin("amount", txn.getAmount()),
                new Bin("timestamp", txn.getTimestamp()));
    }

    public Transaction findByTxnId(String txnId) {
        Key key = new Key(namespace, AerospikeConfig.SET_TRANSACTIONS, txnId);
        Record record = client.get(readPolicy, key);
        if (record == null) return null;
        return mapRecord(txnId, record);
    }

    public List<Transaction> findByClientId(String clientId, int limit) {
        List<Transaction> results = new ArrayList<>();
        ScanPolicy scanPolicy = new ScanPolicy();
        scanPolicy.maxRecords = 0; // scan all, filter in callback
        scanPolicy.concurrentNodes = true;

        client.scanAll(scanPolicy, namespace, AerospikeConfig.SET_TRANSACTIONS,
                (key, record) -> {
                    String recClientId = record.getString("clientId");
                    if (clientId.equals(recClientId)) {
                        synchronized (results) {
                            results.add(mapRecord(record.getString("txnId"), record));
                        }
                    }
                });

        results.sort(Comparator.comparingLong(Transaction::getTimestamp).reversed());
        if (results.size() > limit) {
            return results.subList(0, limit);
        }
        return results;
    }

    private Transaction mapRecord(String txnId, Record record) {
        return Transaction.builder()
                .txnId(txnId != null ? txnId : record.getString("txnId"))
                .clientId(record.getString("clientId"))
                .txnType(record.getString("txnType"))
                .amount(record.getDouble("amount"))
                .timestamp(record.getLong("timestamp"))
                .build();
    }
}
