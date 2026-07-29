package com.bank.anomaly.repository;

import com.bank.anomaly.model.AiFeedback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class AiFeedbackRepository {

    private final JdbcTemplate jdbc;

    private final RowMapper<AiFeedback> rowMapper = (rs, rowNum) ->
            AiFeedback.builder()
                    .txnId(rs.getString("txn_id"))
                    .helpful(rs.getInt("helpful") == 1)
                    .operatorId(rs.getString("operator_id"))
                    .timestamp(rs.getLong("feedback_ts"))
                    .build();

    public AiFeedbackRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(AiFeedback feedback) {
        jdbc.update("""
                MERGE INTO ai_feedback f
                USING (SELECT ? AS txn_id FROM dual) s ON (f.txn_id = s.txn_id)
                WHEN MATCHED THEN UPDATE SET
                    helpful = ?, operator_id = ?, feedback_ts = ?
                WHEN NOT MATCHED THEN INSERT (txn_id, helpful, operator_id, feedback_ts)
                VALUES (?, ?, ?, ?)
                """,
                feedback.getTxnId(),
                feedback.isHelpful() ? 1 : 0, feedback.getOperatorId(), feedback.getTimestamp(),
                feedback.getTxnId(), feedback.isHelpful() ? 1 : 0,
                feedback.getOperatorId(), feedback.getTimestamp());
    }

    public AiFeedback findByTxnId(String txnId) {
        List<AiFeedback> results = jdbc.query(
                "SELECT * FROM ai_feedback WHERE txn_id = ?", rowMapper, txnId);
        return results.isEmpty() ? null : results.get(0);
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        jdbc.query("""
                SELECT
                    SUM(CASE WHEN helpful = 1 THEN 1 ELSE 0 END) AS helpful_cnt,
                    SUM(CASE WHEN helpful = 0 THEN 1 ELSE 0 END) AS not_helpful_cnt,
                    COUNT(*) AS total
                FROM ai_feedback
                """, (rs) -> {
            int helpful = rs.getInt("helpful_cnt");
            int notHelpful = rs.getInt("not_helpful_cnt");
            int total = rs.getInt("total");
            stats.put("helpful", helpful);
            stats.put("notHelpful", notHelpful);
            stats.put("total", total);
            stats.put("helpfulPct", total > 0 ? (helpful * 100.0 / total) : 0.0);
        });

        if (stats.isEmpty()) {
            stats.put("helpful", 0);
            stats.put("notHelpful", 0);
            stats.put("total", 0);
            stats.put("helpfulPct", 0.0);
        }
        return stats;
    }

    public List<String> findRecentNotHelpfulTxnIds(int maxResults) {
        return jdbc.queryForList("""
                SELECT txn_id FROM ai_feedback
                WHERE helpful = 0
                ORDER BY feedback_ts DESC
                FETCH FIRST ? ROWS ONLY
                """, String.class, maxResults);
    }
}
