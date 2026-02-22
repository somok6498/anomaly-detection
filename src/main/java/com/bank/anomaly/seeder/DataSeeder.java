package com.bank.anomaly.seeder;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.policy.WritePolicy;
import com.bank.anomaly.config.AerospikeConfig;
import com.bank.anomaly.model.AnomalyRule;
import com.bank.anomaly.model.RuleType;
import com.bank.anomaly.repository.RuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Seeds Aerospike with realistic transaction data for local testing.
 * Only runs when the "seed" Spring profile is active.
 *
 * Run with:  mvn spring-boot:run -Dspring-boot.run.profiles=seed
 *
 * Generates 10 clients with distinct behavioral patterns:
 *   - CLIENT-001 to CLIENT-005: normal, consistent patterns
 *   - CLIENT-006 to CLIENT-010: similar patterns but with injected anomalies
 *
 * Total records: ~50,000 (enough to build meaningful profiles without waiting too long)
 */
@Component
@Profile("seed")
@Order(1)
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final AerospikeClient client;
    private final String namespace;
    private final WritePolicy writePolicy;
    private final RuleRepository ruleRepository;
    private final Random random = new Random(42); // fixed seed for reproducibility

    // Transaction types
    private static final String[] TXN_TYPES = {"NEFT", "RTGS", "IMPS", "UPI", "IFT"};

    // IFSC codes for beneficiary generation
    private static final String[] IFSC_PREFIXES = {
            "HDFC", "ICIC", "SBIN", "UTIB", "KKBK", "PUNB", "BARB", "IDFB", "YESB", "CNRB"
    };

    public DataSeeder(AerospikeClient client,
                      @Qualifier("aerospikeNamespace") String namespace,
                      WritePolicy writePolicy,
                      RuleRepository ruleRepository) {
        this.client = client;
        this.namespace = namespace;
        this.writePolicy = writePolicy;
        this.ruleRepository = ruleRepository;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("=== Starting data seeding ===");

        seedDefaultRules();
        seedTransactions();

        log.info("=== Data seeding complete ===");
    }

    /**
     * Create default anomaly rules so the system is ready to evaluate transactions.
     */
    private void seedDefaultRules() {
        log.info("Seeding default anomaly rules...");

        // Rule 1: Transaction type anomaly — flag types with <5% frequency
        ruleRepository.save(AnomalyRule.builder()
                .ruleId("RULE-TXN-TYPE")
                .name("Transaction Type Anomaly")
                .description("Flag when client uses a transaction type they rarely or never use")
                .ruleType(RuleType.TRANSACTION_TYPE_ANOMALY)
                .variancePct(5.0)  // types with <5% historical frequency are flagged
                .riskWeight(2.5)
                .enabled(true)
                .params(Map.of("minTypeFrequencyPct", "5.0"))
                .build());

        // Rule 2: TPS spike — flag if hourly count exceeds EWMA by 50%
        ruleRepository.save(AnomalyRule.builder()
                .ruleId("RULE-TPS-SPIKE")
                .name("TPS Spike Detection")
                .description("Flag when hourly transaction count spikes above normal")
                .ruleType(RuleType.TPS_SPIKE)
                .variancePct(50.0)
                .riskWeight(2.0)
                .enabled(true)
                .build());

        // Rule 3: Amount anomaly — flag if single txn amount exceeds EWMA by 100%
        ruleRepository.save(AnomalyRule.builder()
                .ruleId("RULE-AMOUNT")
                .name("Transaction Amount Anomaly")
                .description("Flag unusually large single transactions")
                .ruleType(RuleType.AMOUNT_ANOMALY)
                .variancePct(100.0)
                .riskWeight(3.0)
                .enabled(true)
                .build());

        // Rule 4: Hourly amount anomaly — flag if hourly total exceeds EWMA by 80%
        ruleRepository.save(AnomalyRule.builder()
                .ruleId("RULE-HOURLY-AMT")
                .name("Hourly Amount Anomaly")
                .description("Flag when cumulative hourly amount exceeds normal")
                .ruleType(RuleType.HOURLY_AMOUNT_ANOMALY)
                .variancePct(80.0)
                .riskWeight(1.5)
                .enabled(true)
                .build());

        // Rule 5: Amount per type — flag if amount for specific type exceeds its EWMA by 150%
        ruleRepository.save(AnomalyRule.builder()
                .ruleId("RULE-AMT-PER-TYPE")
                .name("Amount Per Type Anomaly")
                .description("Flag when amount is unusually high for a specific transaction type")
                .ruleType(RuleType.AMOUNT_PER_TYPE_ANOMALY)
                .variancePct(150.0)
                .riskWeight(1.5)
                .enabled(true)
                .params(Map.of("minTypeSamples", "10"))
                .build());

        // Rule 6: Isolation Forest — multi-dimensional anomaly detection
        ruleRepository.save(AnomalyRule.builder()
                .ruleId("RULE-IF")
                .name("Isolation Forest")
                .description("ML-based detection of multi-dimensional anomalies that individual rules miss")
                .ruleType(RuleType.ISOLATION_FOREST)
                .variancePct(60.0)  // anomaly score threshold: 0.60
                .riskWeight(2.0)
                .enabled(true)
                .params(Map.of("numTrees", "100", "sampleSize", "256"))
                .build());

        // Rule 7: Beneficiary rapid repeat — flag 5+ txns to same beneficiary in 1 hour
        ruleRepository.save(AnomalyRule.builder()
                .ruleId("RULE-BENE-RAPID")
                .name("Beneficiary Rapid Repeat")
                .description("Flag when a client sends multiple transactions to the same beneficiary in a short window (structuring/smurfing)")
                .ruleType(RuleType.BENEFICIARY_RAPID_REPEAT)
                .variancePct(0.0)  // not used — threshold via params
                .riskWeight(3.0)
                .enabled(true)
                .params(Map.of("minRepeatCount", "5"))
                .build());

        // Rule 8: Beneficiary concentration — flag disproportionate txn volume to one beneficiary
        ruleRepository.save(AnomalyRule.builder()
                .ruleId("RULE-BENE-CONC")
                .name("Beneficiary Concentration Anomaly")
                .description("Flag when a disproportionate share of transactions go to a single beneficiary")
                .ruleType(RuleType.BENEFICIARY_CONCENTRATION)
                .variancePct(200.0)  // flag if concentration > 3x expected uniform
                .riskWeight(2.0)
                .enabled(true)
                .params(Map.of("absMinConcentrationPct", "5.0"))
                .build());

        // Rule 9: Beneficiary amount repetition — flag repeated identical amounts to same beneficiary
        ruleRepository.save(AnomalyRule.builder()
                .ruleId("RULE-BENE-AMT-REPEAT")
                .name("Beneficiary Amount Repetition")
                .description("Flag when a client repeatedly sends the same amount to the same beneficiary (structuring to evade reporting thresholds)")
                .ruleType(RuleType.BENEFICIARY_AMOUNT_REPETITION)
                .variancePct(0.0)  // not used — threshold via params
                .riskWeight(2.5)
                .enabled(true)
                .params(Map.of("minBeneficiaryTxns", "3", "maxCvPct", "10.0"))
                .build());

        log.info("Seeded 9 default anomaly rules (including Isolation Forest and beneficiary rules)");
    }

    /**
     * Generate 30 days of transaction data for 10 clients.
     *
     * Client profiles:
     *   CLIENT-001: Heavy NEFT user (90% NEFT, 10% RTGS), avg amount 50K, ~200 txns/day
     *   CLIENT-002: UPI-heavy retail (80% UPI, 15% IMPS, 5% NEFT), avg amount 2K, ~500 txns/day
     *   CLIENT-003: Corporate RTGS user (70% RTGS, 20% NEFT, 10% IFT), avg amount 500K, ~50 txns/day
     *   CLIENT-004: Mixed user (equal spread across types), avg amount 25K, ~100 txns/day
     *   CLIENT-005: IMPS specialist (85% IMPS, 15% UPI), avg amount 10K, ~300 txns/day
     *
     *   CLIENT-006 to CLIENT-010: Same base patterns as 001-005 but with injected anomalies
     *   in the last 2 days (unusual types, spiked amounts, TPS bursts).
     */
    private void seedTransactions() {
        log.info("Seeding transaction data for 10 clients over 30 days...");

        Instant endTime = Instant.now();
        Instant startTime = endTime.minus(30, ChronoUnit.DAYS);
        AtomicInteger totalCount = new AtomicInteger(0);

        // Normal clients (001-005)
        seedClient("CLIENT-001", startTime, endTime, 200,
                new String[]{"NEFT", "NEFT", "NEFT", "NEFT", "NEFT", "NEFT", "NEFT", "NEFT", "NEFT", "RTGS"},
                50_000, 15_000, false, totalCount);

        seedClient("CLIENT-002", startTime, endTime, 500,
                new String[]{"UPI", "UPI", "UPI", "UPI", "UPI", "UPI", "UPI", "UPI", "IMPS", "IMPS", "IMPS", "NEFT"},
                2_000, 800, false, totalCount);

        seedClient("CLIENT-003", startTime, endTime, 50,
                new String[]{"RTGS", "RTGS", "RTGS", "RTGS", "RTGS", "RTGS", "RTGS", "NEFT", "NEFT", "IFT"},
                500_000, 150_000, false, totalCount);

        seedClient("CLIENT-004", startTime, endTime, 100,
                new String[]{"NEFT", "RTGS", "IMPS", "UPI", "IFT"},
                25_000, 10_000, false, totalCount);

        seedClient("CLIENT-005", startTime, endTime, 300,
                new String[]{"IMPS", "IMPS", "IMPS", "IMPS", "IMPS", "IMPS", "IMPS", "IMPS", "IMPS", "UPI", "UPI"},
                10_000, 4_000, false, totalCount);

        // Anomalous clients (006-010) — same base patterns, anomalies injected in last 2 days
        seedClient("CLIENT-006", startTime, endTime, 200,
                new String[]{"NEFT", "NEFT", "NEFT", "NEFT", "NEFT", "NEFT", "NEFT", "NEFT", "NEFT", "RTGS"},
                50_000, 15_000, true, totalCount);

        seedClient("CLIENT-007", startTime, endTime, 500,
                new String[]{"UPI", "UPI", "UPI", "UPI", "UPI", "UPI", "UPI", "UPI", "IMPS", "IMPS", "IMPS", "NEFT"},
                2_000, 800, true, totalCount);

        seedClient("CLIENT-008", startTime, endTime, 50,
                new String[]{"RTGS", "RTGS", "RTGS", "RTGS", "RTGS", "RTGS", "RTGS", "NEFT", "NEFT", "IFT"},
                500_000, 150_000, true, totalCount);

        seedClient("CLIENT-009", startTime, endTime, 100,
                new String[]{"NEFT", "RTGS", "IMPS", "UPI", "IFT"},
                25_000, 10_000, true, totalCount);

        seedClient("CLIENT-010", startTime, endTime, 300,
                new String[]{"IMPS", "IMPS", "IMPS", "IMPS", "IMPS", "IMPS", "IMPS", "IMPS", "IMPS", "UPI", "UPI"},
                10_000, 4_000, true, totalCount);

        log.info("Seeded {} total transactions", totalCount.get());
    }

    /**
     * Generate transactions for a single client.
     *
     * @param clientId        the client ID
     * @param start           window start
     * @param end             window end
     * @param txnsPerDay      average transactions per day
     * @param typePool        weighted pool of transaction types (repeats = higher weight)
     * @param avgAmount       average transaction amount
     * @param amountStdDev    standard deviation for amount
     * @param injectAnomalies whether to inject anomalous transactions in the last 2 days
     * @param counter         shared counter for progress tracking
     */
    private void seedClient(String clientId, Instant start, Instant end,
                            int txnsPerDay, String[] typePool,
                            double avgAmount, double amountStdDev,
                            boolean injectAnomalies, AtomicInteger counter) {

        long startMillis = start.toEpochMilli();
        long endMillis = end.toEpochMilli();
        long totalDurationMillis = endMillis - startMillis;
        long anomalyStartMillis = end.minus(2, ChronoUnit.DAYS).toEpochMilli();

        int totalDays = 30;
        int totalTxns = txnsPerDay * totalDays;

        // Generate beneficiary pool for this client (20-50 beneficiaries)
        int poolSize = 20 + random.nextInt(31);
        List<String[]> beneficiaryPool = generateBeneficiaryPool(poolSize);

        // Spread transactions evenly with some jitter
        long avgIntervalMillis = totalDurationMillis / totalTxns;

        long currentTimestamp = startMillis;
        int txnCount = 0;
        int anomalyCount = 0;

        for (int i = 0; i < totalTxns && currentTimestamp < endMillis; i++) {
            boolean isAnomalyWindow = injectAnomalies && currentTimestamp >= anomalyStartMillis;

            String txnType;
            double amount;
            String beneIfsc = null;
            String beneAcct = null;

            if (isAnomalyWindow && random.nextDouble() < 0.15) {
                // 15% of transactions in the anomaly window are anomalous
                int anomalyKind = random.nextInt(3);
                switch (anomalyKind) {
                    case 0:
                        // Type anomaly — use a type the client never/rarely uses
                        txnType = pickUnusualType(typePool);
                        amount = gaussianAmount(avgAmount, amountStdDev);
                        break;
                    case 1:
                        // Amount anomaly — 5x to 10x the normal amount
                        txnType = typePool[random.nextInt(typePool.length)];
                        amount = avgAmount * (5 + random.nextDouble() * 5);
                        break;
                    case 2:
                    default:
                        // Both: unusual type AND high amount
                        txnType = pickUnusualType(typePool);
                        amount = avgAmount * (3 + random.nextDouble() * 7);
                        break;
                }
                anomalyCount++;
            } else {
                // Normal transaction
                txnType = typePool[random.nextInt(typePool.length)];
                amount = gaussianAmount(avgAmount, amountStdDev);
            }

            // Pick a beneficiary (power-law distribution — some beneficiaries more frequent)
            String[] bene = pickBeneficiary(beneficiaryPool);
            beneIfsc = bene[0];
            beneAcct = bene[1];

            // Ensure amount is positive
            amount = Math.max(1.0, Math.round(amount * 100.0) / 100.0);

            String txnId = clientId + "-TXN-" + String.format("%06d", i);
            writeTransaction(txnId, clientId, txnType, amount, currentTimestamp, beneAcct, beneIfsc);

            txnCount++;

            // Add jitter to timestamp (±30% of average interval)
            long jitter = (long) (avgIntervalMillis * 0.3 * (random.nextDouble() * 2 - 1));
            currentTimestamp += avgIntervalMillis + jitter;
        }

        // For anomaly clients: inject a TPS burst (50 extra txns in a single hour)
        if (injectAnomalies) {
            long burstHourStart = anomalyStartMillis + 3600_000L; // 1 hour into anomaly window
            for (int i = 0; i < 50; i++) {
                String txnId = clientId + "-BURST-" + String.format("%03d", i);
                String txnType = typePool[random.nextInt(typePool.length)];
                double amount = gaussianAmount(avgAmount, amountStdDev);
                amount = Math.max(1.0, Math.round(amount * 100.0) / 100.0);
                long ts = burstHourStart + (long) (random.nextDouble() * 3600_000); // within the hour
                String[] bene = pickBeneficiary(beneficiaryPool);
                writeTransaction(txnId, clientId, txnType, amount, ts, bene[1], bene[0]);
                txnCount++;
                anomalyCount++;
            }

            // Inject structuring/smurfing anomaly: 15-25 txns to the SAME beneficiary within 2 hours
            // Amounts are random 8K-50K (look individually normal but the pattern is the anomaly)
            String smurfIfsc = "HDFC0009999";
            String smurfAcct = "9876543210";
            int smurfCount = 15 + random.nextInt(11); // 15-25 txns
            long smurfWindowStart = anomalyStartMillis + 7200_000L; // 2 hours into anomaly window
            log.info("    {} — injecting {} structuring txns to {}:{}", clientId, smurfCount, smurfIfsc, smurfAcct);

            for (int i = 0; i < smurfCount; i++) {
                String txnId = clientId + "-SMURF-" + String.format("%03d", i);
                String txnType = "NEFT"; // structuring typically uses one common type
                // Random amounts between 8K-50K — designed to look individually normal
                double amount = 8000 + random.nextDouble() * 42000;
                amount = Math.round(amount * 100.0) / 100.0;
                long ts = smurfWindowStart + (long) (random.nextDouble() * 7200_000); // within 2 hours
                writeTransaction(txnId, clientId, txnType, amount, ts, smurfAcct, smurfIfsc);
                txnCount++;
                anomalyCount++;
            }

            // Inject amount repetition anomaly: 8-12 txns with nearly identical amounts to same beneficiary
            // Spread across the full 2-day anomaly window (not rapid — slow drip pattern)
            String repeatIfsc = "SBIN0005555";
            String repeatAcct = "5555000001";
            int repeatCount = 8 + random.nextInt(5); // 8-12 txns
            double repeatAmount = 49999.0; // just under a common reporting threshold
            log.info("    {} — injecting {} amount-repetition txns (₹{}) to {}:{}",
                    clientId, repeatCount, repeatAmount, repeatIfsc, repeatAcct);

            long anomalyWindowDuration = endMillis - anomalyStartMillis;
            for (int i = 0; i < repeatCount; i++) {
                String txnId = clientId + "-REPEAT-" + String.format("%03d", i);
                String txnType = "NEFT";
                // Amount varies by ±0.5% to look natural but maintain very low CV
                double amount = repeatAmount * (1.0 + (random.nextDouble() - 0.5) * 0.01);
                amount = Math.round(amount * 100.0) / 100.0;
                // Spread evenly across the 2-day anomaly window
                long ts = anomalyStartMillis + (long) (anomalyWindowDuration * ((double) i / repeatCount))
                        + (long) (random.nextDouble() * 3600_000); // ±1 hour jitter
                ts = Math.min(ts, endMillis - 1);
                writeTransaction(txnId, clientId, txnType, amount, ts, repeatAcct, repeatIfsc);
                txnCount++;
                anomalyCount++;
            }
        }

        int total = counter.addAndGet(txnCount);
        log.info("  {} — {} txns seeded (anomalies: {}). Running total: {}",
                clientId, txnCount, anomalyCount, total);
    }

    /**
     * Generate a pool of beneficiary accounts for a client.
     * Returns list of [ifsc, accountNumber] pairs.
     */
    private List<String[]> generateBeneficiaryPool(int size) {
        List<String[]> pool = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            String ifsc = IFSC_PREFIXES[random.nextInt(IFSC_PREFIXES.length)]
                    + "000" + String.format("%04d", random.nextInt(10000));
            String account = String.valueOf(1000000000L + random.nextInt(900000000));
            pool.add(new String[]{ifsc, account});
        }
        return pool;
    }

    /**
     * Pick a beneficiary from the pool using power-law distribution.
     * Lower-indexed beneficiaries are picked more frequently.
     */
    private String[] pickBeneficiary(List<String[]> pool) {
        // Power-law: index = floor(pool.size * random^2)
        double r = random.nextDouble();
        int idx = (int) (pool.size() * r * r);
        idx = Math.min(idx, pool.size() - 1);
        return pool.get(idx);
    }

    /**
     * Pick a transaction type that is NOT in the client's normal pool.
     */
    private String pickUnusualType(String[] normalPool) {
        // Collect the types present in the normal pool
        java.util.Set<String> normalTypes = new java.util.HashSet<>(java.util.Arrays.asList(normalPool));

        // Find types NOT in the normal pool
        java.util.List<String> unusual = new java.util.ArrayList<>();
        for (String type : TXN_TYPES) {
            if (!normalTypes.contains(type)) {
                unusual.add(type);
            }
        }

        if (unusual.isEmpty()) {
            // All types are in the pool — pick the least represented one
            // (just pick randomly from all types)
            return TXN_TYPES[random.nextInt(TXN_TYPES.length)];
        }

        return unusual.get(random.nextInt(unusual.size()));
    }

    /**
     * Generate a Gaussian-distributed amount.
     */
    private double gaussianAmount(double mean, double stdDev) {
        return mean + random.nextGaussian() * stdDev;
    }

    /**
     * Write a single transaction record to Aerospike.
     */
    private void writeTransaction(String txnId, String clientId, String txnType,
                                  double amount, long timestamp,
                                  String beneAcct, String beneIfsc) {
        Key key = new Key(namespace, AerospikeConfig.SET_TRANSACTIONS, txnId);

        List<Bin> bins = new ArrayList<>(List.of(
                new Bin("txnId", txnId),
                new Bin("clientId", clientId),
                new Bin("txnType", txnType),
                new Bin("amount", amount),
                new Bin("timestamp", timestamp)));

        if (beneAcct != null) {
            bins.add(new Bin("beneAcct", beneAcct));
        }
        if (beneIfsc != null) {
            bins.add(new Bin("beneIfsc", beneIfsc));
        }

        client.put(writePolicy, key, bins.toArray(new Bin[0]));
    }
}
