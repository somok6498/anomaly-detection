package com.bank.anomaly.repository;

import com.bank.anomaly.model.PagedResponse;
import com.bank.anomaly.model.Transaction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class TransactionRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<Transaction> ROW_MAPPER = (rs, rowNum) ->
            Transaction.builder()
                    .txnId(rs.getString("txn_id"))
                    .clientId(rs.getString("client_id"))
                    .txnType(rs.getString("txn_type"))
                    .amount(rs.getDouble("amount"))
                    .timestamp(rs.getLong("timestamp_ms"))
                    .beneficiaryAccount(rs.getString("beneficiary_account"))
                    .beneficiaryIfsc(rs.getString("beneficiary_ifsc"))
                    .build();

    public TransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(Transaction txn) {
        jdbc.update("""
                MERGE INTO transactions t
                USING (SELECT ? AS txn_id FROM dual) s ON (t.txn_id = s.txn_id)
                WHEN NOT MATCHED THEN INSERT (txn_id, client_id, amount, txn_type,
                    beneficiary_account, beneficiary_ifsc, timestamp_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                txn.getTxnId(),
                txn.getTxnId(), txn.getClientId(), txn.getAmount(), txn.getTxnType(),
                txn.getBeneficiaryAccount(), txn.getBeneficiaryIfsc(), txn.getTimestamp());
    }

    public Transaction findByTxnId(String txnId) {
        List<Transaction> results = jdbc.query(
                "SELECT * FROM transactions WHERE txn_id = ?",
                ROW_MAPPER, txnId);
        return results.isEmpty() ? null : results.get(0);
    }

    public PagedResponse<Transaction> findByClientId(String clientId, int limit, Long before) {
        String sql;
        List<Object> params = new ArrayList<>();
        params.add(clientId);

        if (before != null) {
            sql = """
                SELECT * FROM transactions
                WHERE client_id = ? AND timestamp_ms < ?
                ORDER BY timestamp_ms DESC
                FETCH FIRST ? ROWS ONLY
                """;
            params.add(before);
        } else {
            sql = """
                SELECT * FROM transactions
                WHERE client_id = ?
                ORDER BY timestamp_ms DESC
                FETCH FIRST ? ROWS ONLY
                """;
        }
        params.add(limit + 1);

        List<Transaction> results = jdbc.query(sql, ROW_MAPPER, params.toArray());

        boolean hasMore = results.size() > limit;
        List<Transaction> page = hasMore ? new ArrayList<>(results.subList(0, limit)) : results;
        String nextCursor = hasMore ? String.valueOf(page.get(page.size() - 1).getTimestamp()) : null;
        return new PagedResponse<>(page, hasMore, nextCursor);
    }

    public List<Transaction> findByTimeRange(long fromMs, long toMs, String txnType, int maxResults) {
        if (txnType != null) {
            return jdbc.query("""
                    SELECT * FROM transactions
                    WHERE timestamp_ms BETWEEN ? AND ? AND UPPER(txn_type) = UPPER(?)
                    ORDER BY timestamp_ms DESC
                    FETCH FIRST ? ROWS ONLY
                    """, ROW_MAPPER, fromMs, toMs, txnType, maxResults);
        }
        return jdbc.query("""
                SELECT * FROM transactions
                WHERE timestamp_ms BETWEEN ? AND ?
                ORDER BY timestamp_ms DESC
                FETCH FIRST ? ROWS ONLY
                """, ROW_MAPPER, fromMs, toMs, maxResults);
    }

    public long countDistinctClientsByTimeRange(long fromMs, long toMs, String txnType) {
        String sql;
        if (txnType != null) {
            sql = "SELECT COUNT(DISTINCT client_id) FROM transactions WHERE timestamp_ms BETWEEN ? AND ? AND UPPER(txn_type) = UPPER(?)";
            Long count = jdbc.queryForObject(sql, Long.class, fromMs, toMs, txnType);
            return count != null ? count : 0;
        }
        sql = "SELECT COUNT(DISTINCT client_id) FROM transactions WHERE timestamp_ms BETWEEN ? AND ?";
        Long count = jdbc.queryForObject(sql, Long.class, fromMs, toMs);
        return count != null ? count : 0;
    }
}
