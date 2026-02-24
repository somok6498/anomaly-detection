package com.bank.anomaly.repository;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Operation;
import com.aerospike.client.Record;
import com.aerospike.client.policy.Policy;
import com.aerospike.client.policy.ScanPolicy;
import com.aerospike.client.policy.WritePolicy;
import com.bank.anomaly.config.AerospikeConfig;
import com.bank.anomaly.model.ClientProfile;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ClientProfileRepository {

    private static final Logger log = LoggerFactory.getLogger(ClientProfileRepository.class);

    private final AerospikeClient client;
    private final String namespace;
    private final WritePolicy writePolicy;
    private final Policy readPolicy;
    private final ObjectMapper objectMapper;

    public ClientProfileRepository(AerospikeClient client,
                                   @Qualifier("aerospikeNamespace") String namespace,
                                   @Qualifier("defaultWritePolicy") WritePolicy writePolicy,
                                   @Qualifier("defaultReadPolicy") Policy readPolicy) {
        this.client = client;
        this.namespace = namespace;
        this.writePolicy = writePolicy;
        this.readPolicy = readPolicy;
        this.objectMapper = new ObjectMapper();
    }

    public ClientProfile findByClientId(String clientId) {
        Key key = new Key(namespace, AerospikeConfig.SET_CLIENT_PROFILES, clientId);
        Record record = client.get(readPolicy, key);
        if (record == null) {
            return null;
        }
        return mapRecordToProfile(clientId, record);
    }

    public List<ClientProfile> scanAllProfiles() {
        List<ClientProfile> profiles = new ArrayList<>();
        ScanPolicy scanPolicy = new ScanPolicy();
        scanPolicy.concurrentNodes = true;
        scanPolicy.includeBinData = true;

        client.scanAll(scanPolicy, namespace, AerospikeConfig.SET_CLIENT_PROFILES,
                (key, record) -> {
                    try {
                        String clientId = record.getString("clientId");
                        if (clientId != null) {
                            profiles.add(mapRecordToProfile(clientId, record));
                        }
                    } catch (Exception e) {
                        log.warn("Failed to deserialize profile record: {}", e.getMessage());
                    }
                });
        return profiles;
    }

    public void save(ClientProfile profile) {
        Key key = new Key(namespace, AerospikeConfig.SET_CLIENT_PROFILES, profile.getClientId());

        Bin clientIdBin = new Bin("clientId", profile.getClientId());
        Bin txnTypeCountsBin = new Bin("txnTypeCounts", profile.getTxnTypeCounts());
        Bin totalTxnCountBin = new Bin("totalTxnCount", profile.getTotalTxnCount());
        Bin ewmaAmountBin = new Bin("ewmaAmount", profile.getEwmaAmount());
        Bin amountM2Bin = new Bin("amountM2", profile.getAmountM2());
        Bin ewmaHourlyTpsBin = new Bin("ewmaHourlyTps", profile.getEwmaHourlyTps());
        Bin tpsM2Bin = new Bin("tpsM2", profile.getTpsM2());
        Bin completedHoursCountBin = new Bin("completedHours", profile.getCompletedHoursCount());
        Bin avgAmountByTypeBin = new Bin("avgAmtByType", profile.getAvgAmountByType());
        Bin amountM2ByTypeBin = new Bin("amtM2ByType", serializeMap(profile.getAmountM2ByType()));
        Bin amountCountByTypeBin = new Bin("amtCntByType", profile.getAmountCountByType());
        Bin ewmaHourlyAmountBin = new Bin("ewmaHrlyAmt", profile.getEwmaHourlyAmount());
        Bin hourlyAmountM2Bin = new Bin("hrlyAmtM2", profile.getHourlyAmountM2());
        Bin lastUpdatedBin = new Bin("lastUpdated", profile.getLastUpdated());
        Bin lastHourBucketBin = new Bin("lastHrBucket", profile.getLastHourBucket());

        // Beneficiary tracking bins
        Bin beneTxnCntsBin = new Bin("beneTxnCnts", serializeLongMap(profile.getBeneficiaryTxnCounts()));
        Bin distinctBeneBin = new Bin("distinctBene", profile.getDistinctBeneficiaryCount());
        Bin ewmaAmtBeneBin = new Bin("ewmaAmtBene", serializeMap(profile.getEwmaAmountByBeneficiary()));
        Bin amtM2BeneBin = new Bin("amtM2Bene", serializeMap(profile.getAmountM2ByBeneficiary()));

        // Daily tracking bins
        Bin ewmaDailyAmtBin = new Bin("ewmaDailyAmt", profile.getEwmaDailyAmount());
        Bin dailyAmtM2Bin = new Bin("dailyAmtM2", profile.getDailyAmountM2());
        Bin completedDaysBin = new Bin("completedDays", profile.getCompletedDaysCount());
        Bin ewmaDlyNewBnBin = new Bin("ewmaDlyNewBn", profile.getEwmaDailyNewBeneficiaries());
        Bin dlyNewBnM2Bin = new Bin("dlyNewBnM2", profile.getDailyNewBeneM2());
        Bin cmpltDaysBnBin = new Bin("cmpltDaysBn", profile.getCompletedDaysForBeneCount());
        Bin lastDayBktBin = new Bin("lastDayBkt", profile.getLastDayBucket());

        client.put(writePolicy, key,
                clientIdBin, txnTypeCountsBin, totalTxnCountBin,
                ewmaAmountBin, amountM2Bin,
                ewmaHourlyTpsBin, tpsM2Bin, completedHoursCountBin,
                avgAmountByTypeBin, amountM2ByTypeBin, amountCountByTypeBin,
                ewmaHourlyAmountBin, hourlyAmountM2Bin,
                lastUpdatedBin, lastHourBucketBin,
                beneTxnCntsBin, distinctBeneBin, ewmaAmtBeneBin, amtM2BeneBin,
                ewmaDailyAmtBin, dailyAmtM2Bin, completedDaysBin, ewmaDlyNewBnBin,
                dlyNewBnM2Bin, cmpltDaysBnBin, lastDayBktBin);
    }

    /**
     * Atomically increment the hourly transaction counter for a client.
     * Key format: clientId:YYYYMMDDHH
     */
    public long incrementHourlyCounter(String counterKey) {
        Key key = new Key(namespace, AerospikeConfig.SET_HOURLY_COUNTERS, counterKey);
        Bin countBin = new Bin("count", 1);
        Record record = client.operate(writePolicy, key,
                Operation.add(countBin),
                Operation.get("count"));
        return record.getLong("count");
    }

    /**
     * Atomically add to the hourly amount total for a client.
     */
    public void addHourlyAmount(String counterKey, long amountInPaise) {
        Key key = new Key(namespace, AerospikeConfig.SET_HOURLY_COUNTERS, counterKey);
        Bin amountBin = new Bin("totalAmount", amountInPaise);
        client.operate(writePolicy, key, Operation.add(amountBin));
    }

    /**
     * Get the current hourly counter value.
     */
    public long getHourlyCount(String counterKey) {
        Key key = new Key(namespace, AerospikeConfig.SET_HOURLY_COUNTERS, counterKey);
        Record record = client.get(readPolicy, key);
        if (record == null) return 0;
        return record.getLong("count");
    }

    /**
     * Get the current hourly total amount.
     */
    public long getHourlyAmount(String counterKey) {
        Key key = new Key(namespace, AerospikeConfig.SET_HOURLY_COUNTERS, counterKey);
        Record record = client.get(readPolicy, key);
        if (record == null) return 0;
        Long amount = record.getLong("totalAmount");
        return amount != null ? amount : 0;
    }

    // --- Beneficiary hourly counters ---

    public long incrementBeneficiaryCounter(String counterKey) {
        Key key = new Key(namespace, AerospikeConfig.SET_BENEFICIARY_COUNTERS, counterKey);
        Bin countBin = new Bin("count", 1);
        Record record = client.operate(writePolicy, key,
                Operation.add(countBin),
                Operation.get("count"));
        return record.getLong("count");
    }

    public void addBeneficiaryAmount(String counterKey, long amountInPaise) {
        Key key = new Key(namespace, AerospikeConfig.SET_BENEFICIARY_COUNTERS, counterKey);
        Bin amountBin = new Bin("totalAmount", amountInPaise);
        client.operate(writePolicy, key, Operation.add(amountBin));
    }

    public long getBeneficiaryCount(String counterKey) {
        Key key = new Key(namespace, AerospikeConfig.SET_BENEFICIARY_COUNTERS, counterKey);
        Record record = client.get(readPolicy, key);
        if (record == null) return 0;
        return record.getLong("count");
    }

    public long getBeneficiaryAmount(String counterKey) {
        Key key = new Key(namespace, AerospikeConfig.SET_BENEFICIARY_COUNTERS, counterKey);
        Record record = client.get(readPolicy, key);
        if (record == null) return 0;
        Long amount = record.getLong("totalAmount");
        return amount != null ? amount : 0;
    }

    // --- Daily transaction counters ---

    public long incrementDailyCounter(String counterKey) {
        Key key = new Key(namespace, AerospikeConfig.SET_DAILY_COUNTERS, counterKey);
        Bin countBin = new Bin("count", 1);
        Record record = client.operate(writePolicy, key,
                Operation.add(countBin),
                Operation.get("count"));
        return record.getLong("count");
    }

    public void addDailyAmount(String counterKey, long amountInPaise) {
        Key key = new Key(namespace, AerospikeConfig.SET_DAILY_COUNTERS, counterKey);
        Bin amountBin = new Bin("totalAmount", amountInPaise);
        client.operate(writePolicy, key, Operation.add(amountBin));
    }

    public long getDailyCount(String counterKey) {
        Key key = new Key(namespace, AerospikeConfig.SET_DAILY_COUNTERS, counterKey);
        Record record = client.get(readPolicy, key);
        if (record == null) return 0;
        return record.getLong("count");
    }

    public long getDailyAmount(String counterKey) {
        Key key = new Key(namespace, AerospikeConfig.SET_DAILY_COUNTERS, counterKey);
        Record record = client.get(readPolicy, key);
        if (record == null) return 0;
        Long amount = record.getLong("totalAmount");
        return amount != null ? amount : 0;
    }

    // --- Daily new-beneficiary counters ---

    public long incrementDailyNewBeneCounter(String counterKey) {
        Key key = new Key(namespace, AerospikeConfig.SET_DAILY_BENE_COUNTERS, counterKey);
        Bin countBin = new Bin("count", 1);
        Record record = client.operate(writePolicy, key,
                Operation.add(countBin),
                Operation.get("count"));
        return record.getLong("count");
    }

    public long getDailyNewBeneCount(String counterKey) {
        Key key = new Key(namespace, AerospikeConfig.SET_DAILY_BENE_COUNTERS, counterKey);
        Record record = client.get(readPolicy, key);
        if (record == null) return 0;
        return record.getLong("count");
    }

    // --- Daily beneficiary amount counter (for cross-channel detection) ---

    public void addDailyBeneficiaryAmount(String counterKey, long amountInPaise) {
        Key key = new Key(namespace, AerospikeConfig.SET_DAILY_COUNTERS, counterKey);
        Bin amountBin = new Bin("totalAmount", amountInPaise);
        client.operate(writePolicy, key, Operation.add(amountBin));
    }

    public long getDailyBeneficiaryAmount(String counterKey) {
        Key key = new Key(namespace, AerospikeConfig.SET_DAILY_COUNTERS, counterKey);
        Record record = client.get(readPolicy, key);
        if (record == null) return 0;
        Long amount = record.getLong("totalAmount");
        return amount != null ? amount : 0;
    }

    private ClientProfile mapRecordToProfile(String clientId, Record record) {
        ClientProfile profile = new ClientProfile();
        profile.setClientId(clientId);
        profile.setTotalTxnCount(record.getLong("totalTxnCount"));
        profile.setEwmaAmount(record.getDouble("ewmaAmount"));
        profile.setAmountM2(record.getDouble("amountM2"));
        profile.setEwmaHourlyTps(record.getDouble("ewmaHourlyTps"));
        profile.setTpsM2(record.getDouble("tpsM2"));
        profile.setCompletedHoursCount(record.getLong("completedHours"));
        profile.setEwmaHourlyAmount(record.getDouble("ewmaHrlyAmt"));
        profile.setHourlyAmountM2(record.getDouble("hrlyAmtM2"));
        profile.setLastUpdated(record.getLong("lastUpdated"));
        profile.setLastHourBucket(record.getString("lastHrBucket"));

        // Deserialize maps
        @SuppressWarnings("unchecked")
        Map<String, Object> txnTypeCounts = (Map<String, Object>) record.getMap("txnTypeCounts");
        if (txnTypeCounts != null) {
            Map<String, Long> typed = new HashMap<>();
            txnTypeCounts.forEach((k, v) -> typed.put(k, ((Number) v).longValue()));
            profile.setTxnTypeCounts(typed);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> avgAmtByType = (Map<String, Object>) record.getMap("avgAmtByType");
        if (avgAmtByType != null) {
            Map<String, Double> typed = new HashMap<>();
            avgAmtByType.forEach((k, v) -> typed.put(k, ((Number) v).doubleValue()));
            profile.setAvgAmountByType(typed);
        }

        String amtM2ByTypeStr = record.getString("amtM2ByType");
        if (amtM2ByTypeStr != null) {
            profile.setAmountM2ByType(deserializeDoubleMap(amtM2ByTypeStr));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> amtCntByType = (Map<String, Object>) record.getMap("amtCntByType");
        if (amtCntByType != null) {
            Map<String, Long> typed = new HashMap<>();
            amtCntByType.forEach((k, v) -> typed.put(k, ((Number) v).longValue()));
            profile.setAmountCountByType(typed);
        }

        // Beneficiary fields
        Long distinctBene = (Long) record.getValue("distinctBene");
        profile.setDistinctBeneficiaryCount(distinctBene != null ? distinctBene : 0);

        String beneTxnCntsStr = record.getString("beneTxnCnts");
        if (beneTxnCntsStr != null) {
            profile.setBeneficiaryTxnCounts(deserializeLongMap(beneTxnCntsStr));
        }

        String ewmaAmtBeneStr = record.getString("ewmaAmtBene");
        if (ewmaAmtBeneStr != null) {
            profile.setEwmaAmountByBeneficiary(deserializeDoubleMap(ewmaAmtBeneStr));
        }

        String amtM2BeneStr = record.getString("amtM2Bene");
        if (amtM2BeneStr != null) {
            profile.setAmountM2ByBeneficiary(deserializeDoubleMap(amtM2BeneStr));
        }

        // Daily tracking fields
        profile.setEwmaDailyAmount(record.getDouble("ewmaDailyAmt"));
        profile.setDailyAmountM2(record.getDouble("dailyAmtM2"));
        profile.setCompletedDaysCount(record.getLong("completedDays"));
        profile.setEwmaDailyNewBeneficiaries(record.getDouble("ewmaDlyNewBn"));
        profile.setDailyNewBeneM2(record.getDouble("dlyNewBnM2"));
        profile.setCompletedDaysForBeneCount(record.getLong("cmpltDaysBn"));
        profile.setLastDayBucket(record.getString("lastDayBkt"));

        return profile;
    }

    private String serializeLongMap(Map<String, Long> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Long> deserializeLongMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Long>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private String serializeMap(Map<String, Double> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Double> deserializeDoubleMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Double>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
}
