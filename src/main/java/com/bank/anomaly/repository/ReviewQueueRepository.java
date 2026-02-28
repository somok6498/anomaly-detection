package com.bank.anomaly.repository;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.policy.Policy;
import com.aerospike.client.policy.ScanPolicy;
import com.aerospike.client.policy.WritePolicy;
import com.bank.anomaly.config.AerospikeConfig;
import com.bank.anomaly.model.ReviewQueueItem;
import com.bank.anomaly.model.ReviewStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

import com.bank.anomaly.model.PagedResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@Repository
public class ReviewQueueRepository {

    private static final Logger log = LoggerFactory.getLogger(ReviewQueueRepository.class);

    private final AerospikeClient client;
    private final String namespace;
    private final WritePolicy writePolicy;
    private final Policy readPolicy;
    private final ObjectMapper objectMapper;

    public ReviewQueueRepository(AerospikeClient client,
                                  @Qualifier("aerospikeNamespace") String namespace,
                                  @Qualifier("defaultWritePolicy") WritePolicy writePolicy,
                                  @Qualifier("defaultReadPolicy") Policy readPolicy) {
        this.client = client;
        this.namespace = namespace;
        this.writePolicy = writePolicy;
        this.readPolicy = readPolicy;
        this.objectMapper = new ObjectMapper();
    }

    public void save(ReviewQueueItem item) {
        Key key = new Key(namespace, AerospikeConfig.SET_REVIEW_QUEUE, item.getTxnId());

        Bin txnIdBin = new Bin("txnId", item.getTxnId());
        Bin clientIdBin = new Bin("clientId", item.getClientId());
        Bin actionBin = new Bin("action", item.getAction());
        Bin scoreBin = new Bin("compositeScore", item.getCompositeScore());
        Bin riskLevelBin = new Bin("riskLevel", item.getRiskLevel());
        Bin triggeredRulesBin = new Bin("trigRuleIds", serializeList(item.getTriggeredRuleIds()));
        Bin enqueuedAtBin = new Bin("enqueuedAt", item.getEnqueuedAt());
        Bin feedbackStatusBin = new Bin("feedbackStatus", item.getFeedbackStatus().name());
        Bin feedbackAtBin = new Bin("feedbackAt", item.getFeedbackAt());
        Bin feedbackByBin = new Bin("feedbackBy", item.getFeedbackBy() != null ? item.getFeedbackBy() : "");
        Bin deadlineBin = new Bin("autoAcceptDl", item.getAutoAcceptDeadline());

        client.put(writePolicy, key,
                txnIdBin, clientIdBin, actionBin, scoreBin, riskLevelBin,
                triggeredRulesBin, enqueuedAtBin, feedbackStatusBin,
                feedbackAtBin, feedbackByBin, deadlineBin);
    }

    public ReviewQueueItem findByTxnId(String txnId) {
        Key key = new Key(namespace, AerospikeConfig.SET_REVIEW_QUEUE, txnId);
        Record record = client.get(readPolicy, key);
        if (record == null) return null;
        return mapRecord(record);
    }

    public List<ReviewQueueItem> findPending() {
        List<ReviewQueueItem> results = new ArrayList<>();
        ScanPolicy scanPolicy = new ScanPolicy();
        scanPolicy.concurrentNodes = true;

        client.scanAll(scanPolicy, namespace, AerospikeConfig.SET_REVIEW_QUEUE,
                (key, record) -> {
                    try {
                        String status = record.getString("feedbackStatus");
                        if ("PENDING".equals(status)) {
                            synchronized (results) {
                                results.add(mapRecord(record));
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Failed to read review queue record: {}", e.getMessage());
                    }
                });
        return results;
    }

    public PagedResponse<ReviewQueueItem> findByFilters(String action, String clientId,
                                                Long fromDate, Long toDate,
                                                String ruleId, int limit, Long before) {
        List<ReviewQueueItem> results = new ArrayList<>();
        ScanPolicy scanPolicy = new ScanPolicy();
        scanPolicy.concurrentNodes = true;

        client.scanAll(scanPolicy, namespace, AerospikeConfig.SET_REVIEW_QUEUE,
                (key, record) -> {
                    try {
                        // Apply filters
                        if (action != null && !action.isEmpty()) {
                            String recAction = record.getString("action");
                            if (!action.equalsIgnoreCase(recAction)) return;
                        }
                        if (clientId != null && !clientId.isEmpty()) {
                            String recClientId = record.getString("clientId");
                            if (!clientId.equalsIgnoreCase(recClientId)) return;
                        }
                        long enqueuedAt = record.getLong("enqueuedAt");
                        if (before != null && enqueuedAt >= before) return;
                        if (fromDate != null && enqueuedAt < fromDate) return;
                        if (toDate != null && enqueuedAt > toDate) return;

                        if (ruleId != null && !ruleId.isEmpty()) {
                            List<String> ruleIds = deserializeList(record.getString("trigRuleIds"));
                            if (!ruleIds.contains(ruleId)) return;
                        }

                        synchronized (results) {
                            results.add(mapRecord(record));
                        }
                    } catch (Exception e) {
                        log.warn("Failed to filter review queue record: {}", e.getMessage());
                    }
                });

        results.sort(Comparator.comparingLong(ReviewQueueItem::getEnqueuedAt).reversed());
        boolean hasMore = results.size() > limit;
        List<ReviewQueueItem> page = hasMore ? new ArrayList<>(results.subList(0, limit)) : results;
        String nextCursor = hasMore ? String.valueOf(page.get(page.size() - 1).getEnqueuedAt()) : null;
        return new PagedResponse<>(page, hasMore, nextCursor);
    }

    /**
     * Update feedback status. Only updates if current status is PENDING
     * (guard against race with auto-accept).
     * @return true if updated, false if status was already changed
     */
    public boolean updateFeedback(String txnId, ReviewStatus status, String feedbackBy) {
        Key key = new Key(namespace, AerospikeConfig.SET_REVIEW_QUEUE, txnId);
        Record record = client.get(readPolicy, key);
        if (record == null) return false;

        String currentStatus = record.getString("feedbackStatus");
        if (!"PENDING".equals(currentStatus)) {
            log.debug("Queue item {} already has status {}, skipping update", txnId, currentStatus);
            return false;
        }

        Bin feedbackStatusBin = new Bin("feedbackStatus", status.name());
        Bin feedbackAtBin = new Bin("feedbackAt", System.currentTimeMillis());
        Bin feedbackByBin = new Bin("feedbackBy", feedbackBy);

        client.put(writePolicy, key, feedbackStatusBin, feedbackAtBin, feedbackByBin);
        return true;
    }

    public int bulkUpdateFeedback(List<String> txnIds, ReviewStatus status, String feedbackBy) {
        int updated = 0;
        for (String txnId : txnIds) {
            if (updateFeedback(txnId, status, feedbackBy)) {
                updated++;
            }
        }
        return updated;
    }

    public List<ReviewQueueItem> findAllWithFeedback() {
        List<ReviewQueueItem> results = new ArrayList<>();
        ScanPolicy scanPolicy = new ScanPolicy();
        scanPolicy.concurrentNodes = true;

        client.scanAll(scanPolicy, namespace, AerospikeConfig.SET_REVIEW_QUEUE,
                (key, record) -> {
                    try {
                        String status = record.getString("feedbackStatus");
                        if ("TRUE_POSITIVE".equals(status) || "FALSE_POSITIVE".equals(status)) {
                            synchronized (results) {
                                results.add(mapRecord(record));
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Failed to read review queue record: {}", e.getMessage());
                    }
                });
        return results;
    }

    /**
     * Get counts by feedback status for dashboard stats.
     */
    public int[] countByStatus() {
        // [pending, truePositive, falsePositive, autoAccepted]
        int[] counts = new int[4];
        ScanPolicy scanPolicy = new ScanPolicy();
        scanPolicy.concurrentNodes = true;

        client.scanAll(scanPolicy, namespace, AerospikeConfig.SET_REVIEW_QUEUE,
                (key, record) -> {
                    try {
                        String status = record.getString("feedbackStatus");
                        synchronized (counts) {
                            switch (status) {
                                case "PENDING" -> counts[0]++;
                                case "TRUE_POSITIVE" -> counts[1]++;
                                case "FALSE_POSITIVE" -> counts[2]++;
                                case "AUTO_ACCEPTED" -> counts[3]++;
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Failed to count review queue record: {}", e.getMessage());
                    }
                });
        return counts;
    }

    private ReviewQueueItem mapRecord(Record record) {
        String feedbackByStr = record.getString("feedbackBy");
        return ReviewQueueItem.builder()
                .txnId(record.getString("txnId"))
                .clientId(record.getString("clientId"))
                .action(record.getString("action"))
                .compositeScore(record.getDouble("compositeScore"))
                .riskLevel(record.getString("riskLevel"))
                .triggeredRuleIds(deserializeList(record.getString("trigRuleIds")))
                .enqueuedAt(record.getLong("enqueuedAt"))
                .feedbackStatus(ReviewStatus.valueOf(record.getString("feedbackStatus")))
                .feedbackAt(record.getLong("feedbackAt"))
                .feedbackBy(feedbackByStr != null && !feedbackByStr.isEmpty() ? feedbackByStr : null)
                .autoAcceptDeadline(record.getLong("autoAcceptDl"))
                .build();
    }

    private String serializeList(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list != null ? list : Collections.emptyList());
        } catch (Exception e) {
            log.error("Failed to serialize list", e);
            return "[]";
        }
    }

    private List<String> deserializeList(String json) {
        if (json == null || json.isEmpty()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.error("Failed to deserialize list", e);
            return Collections.emptyList();
        }
    }
}
