package com.bank.anomaly.repository;

import com.bank.anomaly.model.ClientProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ClientProfileRepository {

    private static final Logger log = LoggerFactory.getLogger(ClientProfileRepository.class);

    private final JdbcTemplate jdbc;

    public ClientProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ClientProfile findByClientId(String clientId) {
        List<ClientProfile> profiles = jdbc.query(
                "SELECT * FROM client_profiles WHERE client_id = ?",
                (rs, rowNum) -> {
                    ClientProfile p = new ClientProfile();
                    p.setClientId(rs.getString("client_id"));
                    p.setTotalTxnCount(rs.getLong("total_txn_count"));
                    p.setEwmaAmount(rs.getDouble("ewma_amount"));
                    p.setAmountM2(rs.getDouble("amount_m2"));
                    p.setEwmaHourlyTps(rs.getDouble("ewma_hourly_tps"));
                    p.setTpsM2(rs.getDouble("tps_m2"));
                    p.setCompletedHoursCount(rs.getLong("completed_hours_count"));
                    p.setEwmaHourlyAmount(rs.getDouble("ewma_hourly_amount"));
                    p.setHourlyAmountM2(rs.getDouble("hourly_amount_m2"));
                    p.setEwmaDailyAmount(rs.getDouble("ewma_daily_amount"));
                    p.setDailyAmountM2(rs.getDouble("daily_amount_m2"));
                    p.setCompletedDaysCount(rs.getLong("completed_days_count"));
                    p.setEwmaDailyNewBeneficiaries(rs.getDouble("ewma_daily_new_bene"));
                    p.setDailyNewBeneM2(rs.getDouble("daily_new_bene_m2"));
                    p.setCompletedDaysForBeneCount(rs.getLong("completed_days_bene_cnt"));
                    p.setDistinctBeneficiaryCount(rs.getLong("distinct_bene_count"));
                    p.setLastHourBucket(rs.getString("last_hour_bucket"));
                    p.setLastDayBucket(rs.getString("last_day_bucket"));
                    p.setLastUpdated(rs.getLong("last_updated"));
                    return p;
                }, clientId);

        if (profiles.isEmpty()) return null;

        ClientProfile profile = profiles.get(0);
        loadTypeStats(profile);
        loadSeasonalStats(profile);
        loadBeneficiaryStats(profile);
        return profile;
    }

    public List<ClientProfile> scanAllProfiles() {
        List<ClientProfile> profiles = new ArrayList<>();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT client_id FROM client_profiles");
        for (Map<String, Object> row : rows) {
            String clientId = (String) row.get("client_id");
            ClientProfile p = findByClientId(clientId);
            if (p != null) profiles.add(p);
        }
        return profiles;
    }

    public void save(ClientProfile profile) {
        jdbc.update("""
                MERGE INTO client_profiles p
                USING (SELECT ? AS client_id FROM dual) s ON (p.client_id = s.client_id)
                WHEN MATCHED THEN UPDATE SET
                    total_txn_count = ?, ewma_amount = ?, amount_m2 = ?,
                    ewma_hourly_tps = ?, tps_m2 = ?, completed_hours_count = ?,
                    ewma_hourly_amount = ?, hourly_amount_m2 = ?,
                    ewma_daily_amount = ?, daily_amount_m2 = ?, completed_days_count = ?,
                    ewma_daily_new_bene = ?, daily_new_bene_m2 = ?, completed_days_bene_cnt = ?,
                    distinct_bene_count = ?, last_hour_bucket = ?, last_day_bucket = ?,
                    last_updated = ?, version = version + 1
                WHEN NOT MATCHED THEN INSERT (
                    client_id, total_txn_count, ewma_amount, amount_m2,
                    ewma_hourly_tps, tps_m2, completed_hours_count,
                    ewma_hourly_amount, hourly_amount_m2,
                    ewma_daily_amount, daily_amount_m2, completed_days_count,
                    ewma_daily_new_bene, daily_new_bene_m2, completed_days_bene_cnt,
                    distinct_bene_count, last_hour_bucket, last_day_bucket,
                    last_updated, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """,
                // ON key
                profile.getClientId(),
                // UPDATE values
                profile.getTotalTxnCount(), profile.getEwmaAmount(), profile.getAmountM2(),
                profile.getEwmaHourlyTps(), profile.getTpsM2(), profile.getCompletedHoursCount(),
                profile.getEwmaHourlyAmount(), profile.getHourlyAmountM2(),
                profile.getEwmaDailyAmount(), profile.getDailyAmountM2(), profile.getCompletedDaysCount(),
                profile.getEwmaDailyNewBeneficiaries(), profile.getDailyNewBeneM2(), profile.getCompletedDaysForBeneCount(),
                profile.getDistinctBeneficiaryCount(), profile.getLastHourBucket(), profile.getLastDayBucket(),
                profile.getLastUpdated(),
                // INSERT values
                profile.getClientId(),
                profile.getTotalTxnCount(), profile.getEwmaAmount(), profile.getAmountM2(),
                profile.getEwmaHourlyTps(), profile.getTpsM2(), profile.getCompletedHoursCount(),
                profile.getEwmaHourlyAmount(), profile.getHourlyAmountM2(),
                profile.getEwmaDailyAmount(), profile.getDailyAmountM2(), profile.getCompletedDaysCount(),
                profile.getEwmaDailyNewBeneficiaries(), profile.getDailyNewBeneM2(), profile.getCompletedDaysForBeneCount(),
                profile.getDistinctBeneficiaryCount(), profile.getLastHourBucket(), profile.getLastDayBucket(),
                profile.getLastUpdated());

        saveTypeStats(profile);
        saveSeasonalStats(profile);
        saveBeneficiaryStats(profile);
    }

    // ---- Counter operations (using client_counters table with MERGE) ----

    private void mergeCounter(String clientId, String counterType, String bucket, long deltaCount, long deltaSumPaise) {
        jdbc.update("""
                MERGE INTO client_counters c
                USING (SELECT ? AS client_id, ? AS counter_type, ? AS bucket FROM dual) s
                ON (c.client_id = s.client_id AND c.counter_type = s.counter_type AND c.bucket = s.bucket)
                WHEN MATCHED THEN UPDATE SET
                    c.count_val = c.count_val + ?, c.sum_val = c.sum_val + ?
                WHEN NOT MATCHED THEN INSERT (client_id, counter_type, bucket, count_val, sum_val)
                VALUES (?, ?, ?, ?, ?)
                """,
                clientId, counterType, bucket,
                deltaCount, deltaSumPaise,
                clientId, counterType, bucket, deltaCount, deltaSumPaise);
    }

    private long getCounterValue(String clientId, String counterType, String bucket, String column) {
        List<Long> results = jdbc.query(
                "SELECT " + column + " FROM client_counters WHERE client_id = ? AND counter_type = ? AND bucket = ?",
                (rs, rowNum) -> rs.getLong(1),
                clientId, counterType, bucket);
        return results.isEmpty() ? 0 : results.get(0);
    }

    // Hourly counters — counterKey format: "clientId:yyyyMMddHH"
    public long incrementHourlyCounter(String counterKey) {
        String[] parts = splitKey(counterKey);
        mergeCounter(parts[0], "HOURLY_TXN", parts[1], 1, 0);
        return getCounterValue(parts[0], "HOURLY_TXN", parts[1], "count_val");
    }

    public void addHourlyAmount(String counterKey, long amountInPaise) {
        String[] parts = splitKey(counterKey);
        mergeCounter(parts[0], "HOURLY_AMT", parts[1], 0, amountInPaise);
    }

    public long getHourlyCount(String counterKey) {
        String[] parts = splitKey(counterKey);
        return getCounterValue(parts[0], "HOURLY_TXN", parts[1], "count_val");
    }

    public long getHourlyAmount(String counterKey) {
        String[] parts = splitKey(counterKey);
        return getCounterValue(parts[0], "HOURLY_AMT", parts[1], "sum_val");
    }

    // Beneficiary hourly counters — counterKey: "clientId:beneKey:yyyyMMddHH"
    public long incrementBeneficiaryCounter(String counterKey) {
        int lastColon = counterKey.lastIndexOf(':');
        String bucket = counterKey.substring(lastColon + 1);
        String prefix = counterKey.substring(0, lastColon);
        int firstColon = prefix.indexOf(':');
        String clientId = prefix.substring(0, firstColon);
        String beneKey = prefix.substring(firstColon + 1);
        String compositeBucket = beneKey + ":" + bucket;
        mergeCounter(clientId, "BENE_HOURLY_TXN", compositeBucket, 1, 0);
        return getCounterValue(clientId, "BENE_HOURLY_TXN", compositeBucket, "count_val");
    }

    public void addBeneficiaryAmount(String counterKey, long amountInPaise) {
        int lastColon = counterKey.lastIndexOf(':');
        String bucket = counterKey.substring(lastColon + 1);
        String prefix = counterKey.substring(0, lastColon);
        int firstColon = prefix.indexOf(':');
        String clientId = prefix.substring(0, firstColon);
        String beneKey = prefix.substring(firstColon + 1);
        String compositeBucket = beneKey + ":" + bucket;
        mergeCounter(clientId, "BENE_HOURLY_AMT", compositeBucket, 0, amountInPaise);
    }

    public long getBeneficiaryCount(String counterKey) {
        int lastColon = counterKey.lastIndexOf(':');
        String bucket = counterKey.substring(lastColon + 1);
        String prefix = counterKey.substring(0, lastColon);
        int firstColon = prefix.indexOf(':');
        String clientId = prefix.substring(0, firstColon);
        String beneKey = prefix.substring(firstColon + 1);
        String compositeBucket = beneKey + ":" + bucket;
        return getCounterValue(clientId, "BENE_HOURLY_TXN", compositeBucket, "count_val");
    }

    public long getBeneficiaryAmount(String counterKey) {
        int lastColon = counterKey.lastIndexOf(':');
        String bucket = counterKey.substring(lastColon + 1);
        String prefix = counterKey.substring(0, lastColon);
        int firstColon = prefix.indexOf(':');
        String clientId = prefix.substring(0, firstColon);
        String beneKey = prefix.substring(firstColon + 1);
        String compositeBucket = beneKey + ":" + bucket;
        return getCounterValue(clientId, "BENE_HOURLY_AMT", compositeBucket, "sum_val");
    }

    // Daily counters — counterKey: "clientId:yyyyMMdd"
    public long incrementDailyCounter(String counterKey) {
        String[] parts = splitKey(counterKey);
        mergeCounter(parts[0], "DAILY_TXN", parts[1], 1, 0);
        return getCounterValue(parts[0], "DAILY_TXN", parts[1], "count_val");
    }

    public void addDailyAmount(String counterKey, long amountInPaise) {
        String[] parts = splitKey(counterKey);
        mergeCounter(parts[0], "DAILY_AMT", parts[1], 0, amountInPaise);
    }

    public long getDailyCount(String counterKey) {
        String[] parts = splitKey(counterKey);
        return getCounterValue(parts[0], "DAILY_TXN", parts[1], "count_val");
    }

    public long getDailyAmount(String counterKey) {
        String[] parts = splitKey(counterKey);
        return getCounterValue(parts[0], "DAILY_AMT", parts[1], "sum_val");
    }

    // Daily new-beneficiary counters — counterKey: "clientId:newbene:yyyyMMdd"
    public long incrementDailyNewBeneCounter(String counterKey) {
        String[] parts = counterKey.split(":", 3);
        String clientId = parts[0];
        String bucket = parts[2];
        mergeCounter(clientId, "DAILY_NEW_BENE", bucket, 1, 0);
        return getCounterValue(clientId, "DAILY_NEW_BENE", bucket, "count_val");
    }

    public long getDailyNewBeneCount(String counterKey) {
        String[] parts = counterKey.split(":", 3);
        String clientId = parts[0];
        String bucket = parts[2];
        return getCounterValue(clientId, "DAILY_NEW_BENE", bucket, "count_val");
    }

    // Daily beneficiary amount — counterKey: "clientId:beneDaily:yyyyMMdd:beneKey"
    public void addDailyBeneficiaryAmount(String counterKey, long amountInPaise) {
        String[] parts = counterKey.split(":", 4);
        String clientId = parts[0];
        String bucket = parts[2] + ":" + parts[3];
        mergeCounter(clientId, "DAILY_BENE_AMT", bucket, 0, amountInPaise);
    }

    public long getDailyBeneficiaryAmount(String counterKey) {
        String[] parts = counterKey.split(":", 4);
        String clientId = parts[0];
        String bucket = parts[2] + ":" + parts[3];
        return getCounterValue(clientId, "DAILY_BENE_AMT", bucket, "sum_val");
    }

    // ---- Sub-table helpers ----

    private void loadTypeStats(ClientProfile profile) {
        Map<String, Long> txnTypeCounts = new HashMap<>();
        Map<String, Double> avgAmountByType = new HashMap<>();
        Map<String, Double> amountM2ByType = new HashMap<>();
        Map<String, Long> amountCountByType = new HashMap<>();

        jdbc.query("SELECT * FROM client_type_stats WHERE client_id = ?",
                (rs) -> {
                    String type = rs.getString("txn_type");
                    txnTypeCounts.put(type, rs.getLong("txn_count"));
                    avgAmountByType.put(type, rs.getDouble("avg_amount"));
                    amountM2ByType.put(type, rs.getDouble("amount_m2"));
                    amountCountByType.put(type, rs.getLong("amount_count"));
                }, profile.getClientId());

        profile.setTxnTypeCounts(txnTypeCounts);
        profile.setAvgAmountByType(avgAmountByType);
        profile.setAmountM2ByType(amountM2ByType);
        profile.setAmountCountByType(amountCountByType);
    }

    private void loadSeasonalStats(ClientProfile profile) {
        jdbc.query("SELECT * FROM client_seasonal_stats WHERE client_id = ?",
                (rs) -> {
                    String periodType = rs.getString("period_type");
                    String slot = rs.getString("slot");

                    if ("HOURLY".equals(periodType)) {
                        profile.getSeasonalHourlyTps().put(slot, rs.getDouble("tps_mean"));
                        profile.getSeasonalHourlyTpsM2().put(slot, rs.getDouble("tps_m2"));
                        profile.getSeasonalHourlyTpsCnt().put(slot, rs.getLong("tps_count"));
                        profile.getSeasonalHourlyAmt().put(slot, rs.getDouble("amt_mean"));
                        profile.getSeasonalHourlyAmtM2().put(slot, rs.getDouble("amt_m2"));
                        profile.getSeasonalHourlyAmtCnt().put(slot, rs.getLong("amt_count"));
                    } else if ("DAILY".equals(periodType)) {
                        profile.getSeasonalDailyTps().put(slot, rs.getDouble("tps_mean"));
                        profile.getSeasonalDailyTpsM2().put(slot, rs.getDouble("tps_m2"));
                        profile.getSeasonalDailyTpsCnt().put(slot, rs.getLong("tps_count"));
                        profile.getSeasonalDailyAmt().put(slot, rs.getDouble("amt_mean"));
                        profile.getSeasonalDailyAmtM2().put(slot, rs.getDouble("amt_m2"));
                        profile.getSeasonalDailyAmtCnt().put(slot, rs.getLong("amt_count"));
                    }
                }, profile.getClientId());
    }

    private void loadBeneficiaryStats(ClientProfile profile) {
        Map<String, Long> txnCounts = new HashMap<>();
        Map<String, Double> ewmaAmount = new HashMap<>();
        Map<String, Double> amountM2 = new HashMap<>();

        jdbc.query("SELECT * FROM beneficiary_stats WHERE client_id = ?",
                (rs) -> {
                    String beneId = rs.getString("beneficiary_id");
                    txnCounts.put(beneId, rs.getLong("txn_count"));
                    ewmaAmount.put(beneId, rs.getDouble("ewma_amount"));
                    amountM2.put(beneId, rs.getDouble("amount_m2"));
                }, profile.getClientId());

        profile.setBeneficiaryTxnCounts(txnCounts);
        profile.setEwmaAmountByBeneficiary(ewmaAmount);
        profile.setAmountM2ByBeneficiary(amountM2);
    }

    private void saveTypeStats(ClientProfile profile) {
        for (Map.Entry<String, Long> entry : profile.getTxnTypeCounts().entrySet()) {
            String type = entry.getKey();
            jdbc.update("""
                    MERGE INTO client_type_stats s
                    USING (SELECT ? AS client_id, ? AS txn_type FROM dual) d
                    ON (s.client_id = d.client_id AND s.txn_type = d.txn_type)
                    WHEN MATCHED THEN UPDATE SET
                        txn_count = ?, avg_amount = ?, amount_m2 = ?, amount_count = ?
                    WHEN NOT MATCHED THEN INSERT (client_id, txn_type, txn_count, avg_amount, amount_m2, amount_count)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    profile.getClientId(), type,
                    entry.getValue(),
                    profile.getAvgAmountByType().getOrDefault(type, 0.0),
                    profile.getAmountM2ByType().getOrDefault(type, 0.0),
                    profile.getAmountCountByType().getOrDefault(type, 0L),
                    profile.getClientId(), type,
                    entry.getValue(),
                    profile.getAvgAmountByType().getOrDefault(type, 0.0),
                    profile.getAmountM2ByType().getOrDefault(type, 0.0),
                    profile.getAmountCountByType().getOrDefault(type, 0L));
        }
    }

    private void saveSeasonalStats(ClientProfile profile) {
        saveSeasonalMap(profile.getClientId(), "HOURLY",
                profile.getSeasonalHourlyTps(), profile.getSeasonalHourlyTpsM2(), profile.getSeasonalHourlyTpsCnt(),
                profile.getSeasonalHourlyAmt(), profile.getSeasonalHourlyAmtM2(), profile.getSeasonalHourlyAmtCnt());
        saveSeasonalMap(profile.getClientId(), "DAILY",
                profile.getSeasonalDailyTps(), profile.getSeasonalDailyTpsM2(), profile.getSeasonalDailyTpsCnt(),
                profile.getSeasonalDailyAmt(), profile.getSeasonalDailyAmtM2(), profile.getSeasonalDailyAmtCnt());
    }

    private void saveSeasonalMap(String clientId, String periodType,
                                  Map<String, Double> tpsMean, Map<String, Double> tpsM2, Map<String, Long> tpsCnt,
                                  Map<String, Double> amtMean, Map<String, Double> amtM2, Map<String, Long> amtCnt) {
        for (String slot : tpsMean.keySet()) {
            jdbc.update("""
                    MERGE INTO client_seasonal_stats s
                    USING (SELECT ? AS client_id, ? AS period_type, ? AS slot FROM dual) d
                    ON (s.client_id = d.client_id AND s.period_type = d.period_type AND s.slot = d.slot)
                    WHEN MATCHED THEN UPDATE SET
                        tps_mean = ?, tps_m2 = ?, tps_count = ?,
                        amt_mean = ?, amt_m2 = ?, amt_count = ?
                    WHEN NOT MATCHED THEN INSERT (client_id, period_type, slot, tps_mean, tps_m2, tps_count, amt_mean, amt_m2, amt_count)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    clientId, periodType, slot,
                    tpsMean.getOrDefault(slot, 0.0), tpsM2.getOrDefault(slot, 0.0), tpsCnt.getOrDefault(slot, 0L),
                    amtMean.getOrDefault(slot, 0.0), amtM2.getOrDefault(slot, 0.0), amtCnt.getOrDefault(slot, 0L),
                    clientId, periodType, slot,
                    tpsMean.getOrDefault(slot, 0.0), tpsM2.getOrDefault(slot, 0.0), tpsCnt.getOrDefault(slot, 0L),
                    amtMean.getOrDefault(slot, 0.0), amtM2.getOrDefault(slot, 0.0), amtCnt.getOrDefault(slot, 0L));
        }
    }

    private void saveBeneficiaryStats(ClientProfile profile) {
        for (Map.Entry<String, Long> entry : profile.getBeneficiaryTxnCounts().entrySet()) {
            String beneId = entry.getKey();
            jdbc.update("""
                    MERGE INTO beneficiary_stats s
                    USING (SELECT ? AS client_id, ? AS beneficiary_id FROM dual) d
                    ON (s.client_id = d.client_id AND s.beneficiary_id = d.beneficiary_id)
                    WHEN MATCHED THEN UPDATE SET
                        txn_count = ?, total_amount = txn_count * NVL(ewma_amount, 0),
                        ewma_amount = ?, amount_m2 = ?, last_seen = SYSTIMESTAMP
                    WHEN NOT MATCHED THEN INSERT (client_id, beneficiary_id, txn_count, ewma_amount, amount_m2)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    profile.getClientId(), beneId,
                    entry.getValue(),
                    profile.getEwmaAmountByBeneficiary().getOrDefault(beneId, 0.0),
                    profile.getAmountM2ByBeneficiary().getOrDefault(beneId, 0.0),
                    profile.getClientId(), beneId, entry.getValue(),
                    profile.getEwmaAmountByBeneficiary().getOrDefault(beneId, 0.0),
                    profile.getAmountM2ByBeneficiary().getOrDefault(beneId, 0.0));
        }
    }

    private String[] splitKey(String counterKey) {
        int idx = counterKey.indexOf(':');
        return new String[] { counterKey.substring(0, idx), counterKey.substring(idx + 1) };
    }
}
