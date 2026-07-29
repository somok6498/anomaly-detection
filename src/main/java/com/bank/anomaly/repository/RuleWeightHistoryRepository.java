package com.bank.anomaly.repository;

import com.bank.anomaly.model.PagedResponse;
import com.bank.anomaly.model.RuleWeightChange;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class RuleWeightHistoryRepository {

    private final JdbcTemplate jdbc;

    private final RowMapper<RuleWeightChange> rowMapper = (rs, rowNum) ->
            RuleWeightChange.builder()
                    .ruleId(rs.getString("rule_id"))
                    .oldWeight(rs.getDouble("old_weight"))
                    .newWeight(rs.getDouble("new_weight"))
                    .tpCount(rs.getInt("tp_count"))
                    .fpCount(rs.getInt("fp_count"))
                    .tpFpRatio(rs.getDouble("tp_fp_ratio"))
                    .adjustedAt(rs.getLong("adjusted_at"))
                    .build();

    public RuleWeightHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(RuleWeightChange change) {
        String id = change.getRuleId() + "_" + change.getAdjustedAt();
        jdbc.update("""
                INSERT INTO rule_weight_history (id, rule_id, old_weight, new_weight,
                    tp_count, fp_count, tp_fp_ratio, adjusted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, change.getRuleId(), change.getOldWeight(), change.getNewWeight(),
                change.getTpCount(), change.getFpCount(), change.getTpFpRatio(),
                change.getAdjustedAt());
    }

    public PagedResponse<RuleWeightChange> findByRuleId(String ruleId, int limit, Long before) {
        List<Object> params = new ArrayList<>();
        params.add(ruleId);
        String sql;

        if (before != null) {
            sql = """
                SELECT * FROM rule_weight_history
                WHERE rule_id = ? AND adjusted_at < ?
                ORDER BY adjusted_at DESC
                FETCH FIRST ? ROWS ONLY
                """;
            params.add(before);
        } else {
            sql = """
                SELECT * FROM rule_weight_history
                WHERE rule_id = ?
                ORDER BY adjusted_at DESC
                FETCH FIRST ? ROWS ONLY
                """;
        }
        params.add(limit + 1);

        List<RuleWeightChange> results = jdbc.query(sql, rowMapper, params.toArray());

        boolean hasMore = results.size() > limit;
        List<RuleWeightChange> page = hasMore ? new ArrayList<>(results.subList(0, limit)) : results;
        String nextCursor = hasMore ? String.valueOf(page.get(page.size() - 1).getAdjustedAt()) : null;
        return new PagedResponse<>(page, hasMore, nextCursor);
    }

    public PagedResponse<RuleWeightChange> findAll(int limit, Long before) {
        List<Object> params = new ArrayList<>();
        String sql;

        if (before != null) {
            sql = """
                SELECT * FROM rule_weight_history
                WHERE adjusted_at < ?
                ORDER BY adjusted_at DESC
                FETCH FIRST ? ROWS ONLY
                """;
            params.add(before);
        } else {
            sql = """
                SELECT * FROM rule_weight_history
                ORDER BY adjusted_at DESC
                FETCH FIRST ? ROWS ONLY
                """;
        }
        params.add(limit + 1);

        List<RuleWeightChange> results = jdbc.query(sql, rowMapper, params.toArray());

        boolean hasMore = results.size() > limit;
        List<RuleWeightChange> page = hasMore ? new ArrayList<>(results.subList(0, limit)) : results;
        String nextCursor = hasMore ? String.valueOf(page.get(page.size() - 1).getAdjustedAt()) : null;
        return new PagedResponse<>(page, hasMore, nextCursor);
    }
}
