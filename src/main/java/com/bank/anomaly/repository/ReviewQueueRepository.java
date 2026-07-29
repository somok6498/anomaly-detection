package com.bank.anomaly.repository;

import com.bank.anomaly.model.PagedResponse;
import com.bank.anomaly.model.ReviewQueueItem;
import com.bank.anomaly.model.ReviewStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Repository
public class ReviewQueueRepository {

    private static final Logger log = LoggerFactory.getLogger(ReviewQueueRepository.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final RowMapper<ReviewQueueItem> rowMapper = (rs, rowNum) -> {
        String feedbackBy = rs.getString("feedback_by");
        return ReviewQueueItem.builder()
                .txnId(rs.getString("txn_id"))
                .clientId(rs.getString("client_id"))
                .action(rs.getString("action"))
                .compositeScore(rs.getDouble("composite_score"))
                .riskLevel(rs.getString("risk_level"))
                .triggeredRuleIds(deserializeList(rs.getString("triggered_rule_ids")))
                .enqueuedAt(rs.getLong("enqueued_at"))
                .feedbackStatus(ReviewStatus.valueOf(rs.getString("feedback_status")))
                .feedbackAt(rs.getLong("feedback_at"))
                .feedbackBy(feedbackBy != null && !feedbackBy.isEmpty() ? feedbackBy : null)
                .autoAcceptDeadline(rs.getLong("auto_accept_deadline"))
                .build();
    };

    public ReviewQueueRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(ReviewQueueItem item) {
        jdbc.update("""
                MERGE INTO review_queue r
                USING (SELECT ? AS txn_id FROM dual) s ON (r.txn_id = s.txn_id)
                WHEN MATCHED THEN UPDATE SET
                    client_id = ?, action = ?, composite_score = ?, risk_level = ?,
                    triggered_rule_ids = ?, enqueued_at = ?, feedback_status = ?,
                    feedback_at = ?, feedback_by = ?, auto_accept_deadline = ?
                WHEN NOT MATCHED THEN INSERT (
                    txn_id, client_id, action, composite_score, risk_level,
                    triggered_rule_ids, enqueued_at, feedback_status,
                    feedback_at, feedback_by, auto_accept_deadline)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                item.getTxnId(),
                item.getClientId(), item.getAction(), item.getCompositeScore(), item.getRiskLevel(),
                serializeList(item.getTriggeredRuleIds()), item.getEnqueuedAt(),
                item.getFeedbackStatus().name(), item.getFeedbackAt(),
                item.getFeedbackBy() != null ? item.getFeedbackBy() : "",
                item.getAutoAcceptDeadline(),
                item.getTxnId(), item.getClientId(), item.getAction(), item.getCompositeScore(),
                item.getRiskLevel(), serializeList(item.getTriggeredRuleIds()),
                item.getEnqueuedAt(), item.getFeedbackStatus().name(), item.getFeedbackAt(),
                item.getFeedbackBy() != null ? item.getFeedbackBy() : "",
                item.getAutoAcceptDeadline());
    }

    public ReviewQueueItem findByTxnId(String txnId) {
        List<ReviewQueueItem> results = jdbc.query(
                "SELECT * FROM review_queue WHERE txn_id = ?", rowMapper, txnId);
        return results.isEmpty() ? null : results.get(0);
    }

    public List<ReviewQueueItem> findPending() {
        return jdbc.query(
                "SELECT * FROM review_queue WHERE feedback_status = 'PENDING' ORDER BY enqueued_at DESC",
                rowMapper);
    }

    public PagedResponse<ReviewQueueItem> findByFilters(String action, String clientId,
                                                         Long fromDate, Long toDate,
                                                         String ruleId, String feedbackStatus,
                                                         int limit, Long before) {
        StringBuilder sql = new StringBuilder("SELECT * FROM review_queue WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (action != null && !action.isEmpty()) {
            sql.append(" AND UPPER(action) = UPPER(?)");
            params.add(action);
        }
        if (clientId != null && !clientId.isEmpty()) {
            sql.append(" AND UPPER(client_id) = UPPER(?)");
            params.add(clientId);
        }
        if (feedbackStatus != null && !feedbackStatus.isEmpty()) {
            sql.append(" AND UPPER(feedback_status) = UPPER(?)");
            params.add(feedbackStatus);
        }
        if (before != null) {
            sql.append(" AND enqueued_at < ?");
            params.add(before);
        }
        if (fromDate != null) {
            sql.append(" AND enqueued_at >= ?");
            params.add(fromDate);
        }
        if (toDate != null) {
            sql.append(" AND enqueued_at <= ?");
            params.add(toDate);
        }
        if (ruleId != null && !ruleId.isEmpty()) {
            sql.append(" AND triggered_rule_ids LIKE ?");
            params.add("%" + ruleId + "%");
        }

        sql.append(" ORDER BY enqueued_at DESC FETCH FIRST ? ROWS ONLY");
        params.add(limit + 1);

        List<ReviewQueueItem> results = jdbc.query(sql.toString(), rowMapper, params.toArray());

        boolean hasMore = results.size() > limit;
        List<ReviewQueueItem> page = hasMore ? new ArrayList<>(results.subList(0, limit)) : results;
        String nextCursor = hasMore ? String.valueOf(page.get(page.size() - 1).getEnqueuedAt()) : null;
        return new PagedResponse<>(page, hasMore, nextCursor);
    }

    public boolean updateFeedback(String txnId, ReviewStatus status, String feedbackBy) {
        int updated = jdbc.update("""
                UPDATE review_queue SET feedback_status = ?, feedback_at = ?, feedback_by = ?
                WHERE txn_id = ? AND feedback_status = 'PENDING'
                """,
                status.name(), System.currentTimeMillis(), feedbackBy, txnId);
        return updated > 0;
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
        return findAllWithFeedback(null, null);
    }

    public List<ReviewQueueItem> findAllWithFeedback(Long fromDate, Long toDate) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM review_queue WHERE feedback_status IN ('TRUE_POSITIVE', 'FALSE_POSITIVE')");
        List<Object> params = new ArrayList<>();

        if (fromDate != null) {
            sql.append(" AND enqueued_at >= ?");
            params.add(fromDate);
        }
        if (toDate != null) {
            sql.append(" AND enqueued_at <= ?");
            params.add(toDate);
        }

        return jdbc.query(sql.toString(), rowMapper, params.toArray());
    }

    public int[] countByStatus() {
        return countByStatus(null, null);
    }

    public int[] countByStatus(Long fromDate, Long toDate) {
        StringBuilder sql = new StringBuilder(
                "SELECT feedback_status, COUNT(*) AS cnt FROM review_queue WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (fromDate != null) {
            sql.append(" AND enqueued_at >= ?");
            params.add(fromDate);
        }
        if (toDate != null) {
            sql.append(" AND enqueued_at <= ?");
            params.add(toDate);
        }
        sql.append(" GROUP BY feedback_status");

        int[] counts = new int[4]; // [pending, truePositive, falsePositive, autoAccepted]
        jdbc.query(sql.toString(), (rs) -> {
            String status = rs.getString("feedback_status");
            int cnt = rs.getInt("cnt");
            switch (status) {
                case "PENDING" -> counts[0] = cnt;
                case "TRUE_POSITIVE" -> counts[1] = cnt;
                case "FALSE_POSITIVE" -> counts[2] = cnt;
                case "AUTO_ACCEPTED" -> counts[3] = cnt;
            }
        }, params.toArray());
        return counts;
    }

    private String serializeList(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list != null ? list : Collections.emptyList());
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> deserializeList(String json) {
        if (json == null || json.isEmpty()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
