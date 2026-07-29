package com.bank.anomaly.repository;

import com.bank.anomaly.model.EvaluationResult;
import com.bank.anomaly.model.PagedResponse;
import com.bank.anomaly.model.RiskLevel;
import com.bank.anomaly.model.RuleResult;
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
public class RiskResultRepository {

    private static final Logger log = LoggerFactory.getLogger(RiskResultRepository.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final RowMapper<EvaluationResult> rowMapper = (rs, rowNum) ->
            EvaluationResult.builder()
                    .txnId(rs.getString("txn_id"))
                    .clientId(rs.getString("client_id"))
                    .compositeScore(rs.getDouble("composite_score"))
                    .riskLevel(RiskLevel.valueOf(rs.getString("risk_level")))
                    .action(rs.getString("action"))
                    .evaluatedAt(rs.getLong("evaluated_at"))
                    .ruleResults(deserializeRuleResults(rs.getString("rule_results")))
                    .triggeredRuleCount(rs.getInt("triggered_rule_count"))
                    .breadthBonus(rs.getDouble("breadth_bonus"))
                    .aiExplanation(rs.getString("ai_explanation"))
                    .attackPattern(rs.getString("attack_pattern"))
                    .build();

    public RiskResultRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(EvaluationResult result) {
        jdbc.update(conn -> {
            var ps = conn.prepareStatement("""
                    MERGE INTO evaluation_results r
                    USING (SELECT ? AS txn_id FROM dual) s ON (r.txn_id = s.txn_id)
                    WHEN NOT MATCHED THEN INSERT (
                        txn_id, client_id, composite_score, risk_level, action,
                        rule_results, evaluated_at, triggered_rule_count, breadth_bonus,
                        ai_explanation, attack_pattern)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            ps.setString(1, result.getTxnId());
            ps.setString(2, result.getTxnId());
            ps.setString(3, result.getClientId());
            ps.setDouble(4, result.getCompositeScore());
            ps.setString(5, result.getRiskLevel().name());
            ps.setString(6, result.getAction());
            ps.setString(7, serializeRuleResults(result.getRuleResults()));
            ps.setLong(8, result.getEvaluatedAt());
            ps.setInt(9, result.getTriggeredRuleCount());
            ps.setDouble(10, result.getBreadthBonus());
            if (result.getAiExplanation() != null) {
                ps.setString(11, result.getAiExplanation());
            } else {
                ps.setNull(11, java.sql.Types.CLOB);
            }
            if (result.getAttackPattern() != null) {
                ps.setString(12, result.getAttackPattern());
            } else {
                ps.setNull(12, java.sql.Types.VARCHAR);
            }
            return ps;
        });
    }

    public EvaluationResult findByTxnId(String txnId) {
        List<EvaluationResult> results = jdbc.query(
                "SELECT * FROM evaluation_results WHERE txn_id = ?", rowMapper, txnId);
        return results.isEmpty() ? null : results.get(0);
    }

    public PagedResponse<EvaluationResult> findByClientId(String clientId, int limit, Long before) {
        List<Object> params = new ArrayList<>();
        params.add(clientId);
        String sql;

        if (before != null) {
            sql = """
                SELECT * FROM evaluation_results
                WHERE client_id = ? AND evaluated_at < ?
                ORDER BY evaluated_at DESC
                FETCH FIRST ? ROWS ONLY
                """;
            params.add(before);
        } else {
            sql = """
                SELECT * FROM evaluation_results
                WHERE client_id = ?
                ORDER BY evaluated_at DESC
                FETCH FIRST ? ROWS ONLY
                """;
        }
        params.add(limit + 1);

        List<EvaluationResult> results = jdbc.query(sql, rowMapper, params.toArray());

        boolean hasMore = results.size() > limit;
        List<EvaluationResult> page = hasMore ? new ArrayList<>(results.subList(0, limit)) : results;
        String nextCursor = hasMore ? String.valueOf(page.get(page.size() - 1).getEvaluatedAt()) : null;
        return new PagedResponse<>(page, hasMore, nextCursor);
    }

    public List<EvaluationResult> findByTimeRange(long fromMs, long toMs,
                                                   String riskLevel, String action, int maxResults) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM evaluation_results WHERE evaluated_at BETWEEN ? AND ?");
        List<Object> params = new ArrayList<>(List.of(fromMs, toMs));

        if (riskLevel != null) {
            sql.append(" AND UPPER(risk_level) = UPPER(?)");
            params.add(riskLevel);
        }
        if (action != null) {
            sql.append(" AND UPPER(action) = UPPER(?)");
            params.add(action);
        }
        sql.append(" ORDER BY evaluated_at DESC FETCH FIRST ? ROWS ONLY");
        params.add(maxResults);

        return jdbc.query(sql.toString(), rowMapper, params.toArray());
    }

    public long countDistinctClientsByTimeRange(long fromMs, long toMs, String riskLevel, String action) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(DISTINCT client_id) FROM evaluation_results WHERE evaluated_at BETWEEN ? AND ?");
        List<Object> params = new ArrayList<>(List.of(fromMs, toMs));

        if (riskLevel != null) {
            sql.append(" AND UPPER(risk_level) = UPPER(?)");
            params.add(riskLevel);
        }
        if (action != null) {
            sql.append(" AND UPPER(action) = UPPER(?)");
            params.add(action);
        }

        Long count = jdbc.queryForObject(sql.toString(), Long.class, params.toArray());
        return count != null ? count : 0;
    }

    public void updateAiExplanation(String txnId, String aiExplanation) {
        jdbc.update("UPDATE evaluation_results SET ai_explanation = ? WHERE txn_id = ?",
                aiExplanation, txnId);
    }

    public void updateAttackPattern(String txnId, String attackPattern) {
        jdbc.update("UPDATE evaluation_results SET attack_pattern = ? WHERE txn_id = ?",
                attackPattern, txnId);
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
