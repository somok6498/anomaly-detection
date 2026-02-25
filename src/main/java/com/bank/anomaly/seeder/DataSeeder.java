package com.bank.anomaly.seeder;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.policy.WritePolicy;
import com.bank.anomaly.config.AerospikeConfig;
import com.bank.anomaly.config.RiskThresholdConfig;
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

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
    private final RiskThresholdConfig thresholdConfig;
    private final Random random = new Random(42); // fixed seed for reproducibility

    // IFSC codes for beneficiary generation
    private static final String[] IFSC_PREFIXES = {
            "HDFC", "ICIC", "SBIN", "UTIB", "KKBK", "PUNB", "BARB", "IDFB", "YESB", "CNRB"
    };

    public DataSeeder(AerospikeClient client,
                      @Qualifier("aerospikeNamespace") String namespace,
                      WritePolicy writePolicy,
                      RuleRepository ruleRepository,
                      RiskThresholdConfig thresholdConfig) {
        this.client = client;
        this.namespace = namespace;
        this.writePolicy = writePolicy;
        this.ruleRepository = ruleRepository;
        this.thresholdConfig = thresholdConfig;
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

        // Rule 10: Daily cumulative amount — detect low-and-slow drip structuring
        ruleRepository.save(AnomalyRule.builder()
                .ruleId("RULE-DAILY-AMT")
                .name("Daily Cumulative Amount")
                .description("Flag when total daily transaction amount exceeds client's normal daily volume (drip structuring)")
                .ruleType(RuleType.DAILY_CUMULATIVE_AMOUNT)
                .variancePct(150.0)
                .riskWeight(2.5)
                .enabled(true)
                .params(Map.of("minDays", "3"))
                .build());

        // Rule 11: New beneficiary velocity — detect round-robin mule fan-out
        ruleRepository.save(AnomalyRule.builder()
                .ruleId("RULE-NEW-BENE-VEL")
                .name("New Beneficiary Velocity")
                .description("Flag when too many first-time beneficiaries are transacted with in a single day (mule fan-out)")
                .ruleType(RuleType.NEW_BENEFICIARY_VELOCITY)
                .variancePct(200.0)
                .riskWeight(3.5)
                .enabled(true)
                .params(Map.of("maxNewBenePerDay", "5", "minProfileDays", "3"))
                .build());

        // Rule 12: Dormancy reactivation — detect sudden activity on dormant accounts
        ruleRepository.save(AnomalyRule.builder()
                .ruleId("RULE-DORMANCY")
                .name("Dormancy Reactivation")
                .description("Flag when a dormant account suddenly becomes active after extended inactivity")
                .ruleType(RuleType.DORMANCY_REACTIVATION)
                .variancePct(0.0)  // not used — threshold via params
                .riskWeight(3.0)
                .enabled(true)
                .params(Map.of("dormancyDays", "30"))
                .build());

        // Rule 13: Cross-channel beneficiary amount — detect splitting across txn types
        ruleRepository.save(AnomalyRule.builder()
                .ruleId("RULE-CROSS-CHAN-BENE")
                .name("Cross-Channel Beneficiary Amount")
                .description("Flag when same beneficiary receives large total across multiple transaction types in a day")
                .ruleType(RuleType.CROSS_CHANNEL_BENEFICIARY_AMOUNT)
                .variancePct(150.0)
                .riskWeight(2.5)
                .enabled(true)
                .params(Map.of("minDays", "3"))
                .build());

        // Rule 14: Seasonal deviation — detect off-season anomalies (3AM spikes, weekend surges)
        ruleRepository.save(AnomalyRule.builder()
                .ruleId("RULE-SEASONAL")
                .name("Seasonal Deviation")
                .description("Flag when transaction volume or amounts deviate from the expected pattern for this time-of-day or day-of-week")
                .ruleType(RuleType.SEASONAL_DEVIATION)
                .variancePct(80.0)
                .riskWeight(2.0)
                .enabled(true)
                .params(Map.of("minSeasonalSamples", "4"))
                .build());

        // Rule 15: Mule network detection — graph-based detection of shared beneficiary networks
        ruleRepository.save(AnomalyRule.builder()
                .ruleId("RULE-MULE-NET")
                .name("Mule Network Detection")
                .description("Graph-based detection of mule networks via shared beneficiaries, fan-in convergence, and cluster density")
                .ruleType(RuleType.MULE_NETWORK)
                .variancePct(25.0)  // composite score threshold
                .riskWeight(4.0)    // high weight — mule detection is critical
                .enabled(true)
                .params(Map.of("minFanIn", "2", "sharedBenePctThreshold", "20.0",
                        "densityThreshold", "0.3"))
                .build());

        log.info("Seeded 15 default anomaly rules");
    }

    /**
     * Generate 60 days of transaction data for 11 clients with time-aware patterns.
     *
     * Time-aware patterns:
     *   - Weekdays: 90% of txns during business hours (09-17 UTC), 10% off-peak
     *   - Weekends: 30% of weekday volume
     *
     * Client profiles:
     *   CLIENT-001: Heavy NEFT user (90% NEFT, 10% RTGS), avg amount 50K, ~200 txns/day
     *   CLIENT-002: UPI-heavy retail (80% UPI, 15% IMPS, 5% NEFT), avg amount 2K, ~500 txns/day
     *   CLIENT-003: Corporate RTGS user (70% RTGS, 20% NEFT, 10% IFT), avg amount 500K, ~50 txns/day
     *   CLIENT-004: Mixed user (equal spread across types), avg amount 25K, ~100 txns/day
     *   CLIENT-005: IMPS specialist (85% IMPS, 15% UPI), avg amount 10K, ~300 txns/day
     *
     *   CLIENT-006 to CLIENT-010: Same base patterns as 001-005 but with injected anomalies
     *   in the last 2 days (unusual types, spiked amounts, TPS bursts, 3AM activity, Sunday surges).
     */
    private void seedTransactions() {
        log.info("Seeding transaction data for 11 clients over 60 days...");

        Instant endTime = Instant.now();
        Instant startTime = endTime.minus(60, ChronoUnit.DAYS);
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

        // CLIENT-011: Dormant account — 28 days of history ending 2+ days ago, then one reactivation txn
        seedDormantClient("CLIENT-011", startTime, endTime, totalCount);

        // Mule network patterns — shared beneficiaries across CLIENT-007, CLIENT-008, CLIENT-009
        seedMuleNetworkPatterns(endTime, totalCount);

        log.info("Seeded {} total transactions", totalCount.get());
    }

    /**
     * Seed a dormant client: normal activity for 28 days, then 2+ day gap, then a reactivation txn.
     */
    private void seedDormantClient(String clientId, Instant start, Instant end, AtomicInteger counter) {
        log.info("  {} — seeding dormant client (56 days active, then 2+ day gap)", clientId);

        Instant dormancyStart = end.minus(2, ChronoUnit.DAYS).minus(6, ChronoUnit.HOURS);
        Instant activeEnd = dormancyStart; // activity stops here
        long startMillis = start.toEpochMilli();
        long activeEndMillis = activeEnd.toEpochMilli();

        int txnsPerDay = 100;
        int activeDays = 56;
        int totalTxns = txnsPerDay * activeDays;
        double avgAmount = 30_000;
        double amountStdDev = 10_000;
        String[] typePool = {"NEFT", "NEFT", "NEFT", "NEFT", "NEFT", "RTGS", "IMPS", "UPI"};

        int poolSize = 25;
        List<String[]> beneficiaryPool = generateBeneficiaryPool(poolSize);

        long totalDurationMillis = activeEndMillis - startMillis;
        long avgIntervalMillis = totalDurationMillis / totalTxns;
        long currentTimestamp = startMillis;
        int txnCount = 0;

        for (int i = 0; i < totalTxns && currentTimestamp < activeEndMillis; i++) {
            String txnType = typePool[random.nextInt(typePool.length)];
            double amount = Math.max(1.0, Math.round(gaussianAmount(avgAmount, amountStdDev) * 100.0) / 100.0);
            String txnId = clientId + "-TXN-" + String.format("%06d", i);
            String[] bene = pickBeneficiary(beneficiaryPool);
            writeTransaction(txnId, clientId, txnType, amount, currentTimestamp, bene[1], bene[0]);
            txnCount++;

            long jitter = (long) (avgIntervalMillis * 0.3 * (random.nextDouble() * 2 - 1));
            currentTimestamp += avgIntervalMillis + jitter;
        }

        // Reactivation transaction — right at "now" (triggers Rule 12: Dormancy Reactivation)
        String reactivationTxnId = clientId + "-REACTIVATION-001";
        String[] bene = pickBeneficiary(beneficiaryPool);
        writeTransaction(reactivationTxnId, clientId, "NEFT", 50_000,
                end.toEpochMilli() - 60_000, bene[1], bene[0]);
        txnCount++;

        int total = counter.addAndGet(txnCount);
        log.info("  {} — {} txns seeded (dormant pattern, 2+ day gap). Running total: {}",
                clientId, txnCount, total);
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

        long endMillis = end.toEpochMilli();
        long anomalyStartMillis = end.minus(2, ChronoUnit.DAYS).toEpochMilli();

        int totalDays = 60;

        // Generate beneficiary pool for this client (20-50 beneficiaries)
        int poolSize = 20 + random.nextInt(31);
        List<String[]> beneficiaryPool = generateBeneficiaryPool(poolSize);

        int txnCount = 0;
        int anomalyCount = 0;
        int globalTxnIdx = 0;

        // Day-by-day iteration with business-hour bias and weekend dips
        for (int day = 0; day < totalDays; day++) {
            Instant dayStart = start.plus(day, ChronoUnit.DAYS);
            LocalDate localDate = dayStart.atZone(ZoneOffset.UTC).toLocalDate();
            DayOfWeek dow = localDate.getDayOfWeek();
            boolean isWeekend = (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY);

            // Weekend: 30% of weekday volume
            int dayTxnCount = isWeekend ? (int) (txnsPerDay * 0.3) : txnsPerDay;
            // Add ±10% daily jitter
            dayTxnCount = (int) (dayTxnCount * (0.9 + random.nextDouble() * 0.2));
            dayTxnCount = Math.max(1, dayTxnCount);

            long dayStartMillis = dayStart.toEpochMilli();

            for (int i = 0; i < dayTxnCount; i++) {
                // Pick hour with business-hour bias: 90% during 09-17 UTC, 10% off-peak
                int hour;
                if (random.nextDouble() < 0.9) {
                    // Business hours: 09-17 (8 hours)
                    hour = 9 + random.nextInt(8);
                } else {
                    // Off-peak hours: 00-08, 18-23 (16 hours)
                    int offPeakIdx = random.nextInt(16);
                    hour = offPeakIdx < 9 ? offPeakIdx : (offPeakIdx - 9 + 18);
                }
                // Random minute+second within the hour
                long tsMillis = dayStartMillis + hour * 3600_000L
                        + (long) (random.nextDouble() * 3600_000);
                if (tsMillis >= endMillis) continue;

                boolean isAnomalyWindow = injectAnomalies && tsMillis >= anomalyStartMillis;

                String txnType;
                double amount;

                if (isAnomalyWindow && random.nextDouble() < 0.15) {
                    int anomalyKind = random.nextInt(3);
                    switch (anomalyKind) {
                        case 0:
                            txnType = pickUnusualType(typePool);
                            amount = gaussianAmount(avgAmount, amountStdDev);
                            break;
                        case 1:
                            txnType = typePool[random.nextInt(typePool.length)];
                            amount = avgAmount * (5 + random.nextDouble() * 5);
                            break;
                        case 2:
                        default:
                            txnType = pickUnusualType(typePool);
                            amount = avgAmount * (3 + random.nextDouble() * 7);
                            break;
                    }
                    anomalyCount++;
                } else {
                    txnType = typePool[random.nextInt(typePool.length)];
                    amount = gaussianAmount(avgAmount, amountStdDev);
                }

                String[] bene = pickBeneficiary(beneficiaryPool);

                amount = Math.max(1.0, Math.round(amount * 100.0) / 100.0);

                String txnId = clientId + "-TXN-" + String.format("%06d", globalTxnIdx++);
                writeTransaction(txnId, clientId, txnType, amount, tsMillis, bene[1], bene[0]);
                txnCount++;
            }
        }

        // For anomaly clients: inject various anomaly patterns
        if (injectAnomalies) {
            // === Seasonal anomaly: TPS burst at 3 AM (off-peak) ===
            // Find a day in the anomaly window and place 30 txns at 3 AM
            long seasonalBurstDay = anomalyStartMillis;
            long burst3amStart = seasonalBurstDay + 3 * 3600_000L; // 3 AM
            log.info("    {} — injecting 30 txns at 3 AM for seasonal deviation", clientId);
            for (int i = 0; i < 30; i++) {
                String txnId = clientId + "-SEASONAL3AM-" + String.format("%03d", i);
                String txnType = typePool[random.nextInt(typePool.length)];
                double amount = gaussianAmount(avgAmount, amountStdDev);
                amount = Math.max(1.0, Math.round(amount * 100.0) / 100.0);
                long ts = burst3amStart + (long) (random.nextDouble() * 3600_000);
                String[] bene = pickBeneficiary(beneficiaryPool);
                writeTransaction(txnId, clientId, txnType, amount, ts, bene[1], bene[0]);
                txnCount++;
                anomalyCount++;
            }

            // === Seasonal anomaly: Sunday high-volume (for low-weekend clients) ===
            // Find the Sunday in/near the anomaly window and inject weekday-level volume
            Instant anomalyInstant = Instant.ofEpochMilli(anomalyStartMillis);
            LocalDate anomalyDate = anomalyInstant.atZone(ZoneOffset.UTC).toLocalDate();
            // Find next Sunday
            LocalDate sunday = anomalyDate;
            while (sunday.getDayOfWeek() != DayOfWeek.SUNDAY) {
                sunday = sunday.plusDays(1);
            }
            long sundayStart = sunday.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            if (sundayStart < endMillis) {
                int sundayBurst = txnsPerDay; // full weekday volume on Sunday
                log.info("    {} — injecting {} Sunday txns for seasonal deviation", clientId, sundayBurst);
                for (int i = 0; i < sundayBurst; i++) {
                    String txnId = clientId + "-SUNDAYBURST-" + String.format("%04d", i);
                    String txnType = typePool[random.nextInt(typePool.length)];
                    double amount = gaussianAmount(avgAmount, amountStdDev);
                    amount = Math.max(1.0, Math.round(amount * 100.0) / 100.0);
                    int hour = 9 + random.nextInt(8); // business hours on a Sunday
                    long ts = sundayStart + hour * 3600_000L + (long) (random.nextDouble() * 3600_000);
                    ts = Math.min(ts, endMillis - 1);
                    String[] bene = pickBeneficiary(beneficiaryPool);
                    writeTransaction(txnId, clientId, txnType, amount, ts, bene[1], bene[0]);
                    txnCount++;
                    anomalyCount++;
                }
            }

            // TPS burst (50 extra txns in a single hour)
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

            // === Rule 10: Drip structuring — many small txns summing to large daily total ===
            // Inject 40-60 small txns on a single day, each individually normal but total is huge
            int dripCount = 40 + random.nextInt(21);
            long dripDayStart = anomalyStartMillis + 12 * 3600_000L; // 12h into anomaly window
            log.info("    {} — injecting {} drip-structuring txns (Rule 10)", clientId, dripCount);
            for (int i = 0; i < dripCount; i++) {
                String txnId = clientId + "-DRIP-" + String.format("%03d", i);
                String txnType = typePool[random.nextInt(typePool.length)];
                // Each txn is small/normal-looking but they add up
                double amount = Math.max(1.0, gaussianAmount(avgAmount * 0.8, amountStdDev * 0.3));
                amount = Math.round(amount * 100.0) / 100.0;
                long ts = dripDayStart + (long) (random.nextDouble() * 12 * 3600_000); // spread over 12h
                String[] bene = pickBeneficiary(beneficiaryPool);
                writeTransaction(txnId, clientId, txnType, amount, ts, bene[1], bene[0]);
                txnCount++;
                anomalyCount++;
            }

            // === Rule 11: Fan-out — many txns to brand-new beneficiaries in one day ===
            int fanOutCount = 8 + random.nextInt(5); // 8-12 new beneficiaries
            long fanOutDayStart = anomalyStartMillis + 24 * 3600_000L; // day 2 of anomaly window
            log.info("    {} — injecting {} fan-out txns to new beneficiaries (Rule 11)", clientId, fanOutCount);
            for (int i = 0; i < fanOutCount; i++) {
                String txnId = clientId + "-FANOUT-" + String.format("%03d", i);
                String txnType = typePool[random.nextInt(typePool.length)];
                double amount = Math.max(1.0, gaussianAmount(avgAmount, amountStdDev));
                amount = Math.round(amount * 100.0) / 100.0;
                long ts = fanOutDayStart + (long) (random.nextDouble() * 12 * 3600_000);
                // Generate brand-new unique beneficiary for each txn
                String fanIfsc = IFSC_PREFIXES[random.nextInt(IFSC_PREFIXES.length)]
                        + "000" + String.format("%04d", 9000 + i);
                String fanAcct = "FANOUT" + String.format("%06d", i);
                writeTransaction(txnId, clientId, txnType, amount, ts, fanAcct, fanIfsc);
                txnCount++;
                anomalyCount++;
            }

            // === Rule 13: Cross-channel splitting — same beneficiary via multiple txn types ===
            List<String> txnTypes = thresholdConfig.getTransactionTypes();
            String crossIfsc = "ICIC0008888";
            String crossAcct = "8888000001";
            int crossCount = Math.min(txnTypes.size(), 5); // one txn per type
            long crossDayStart = anomalyStartMillis + 36 * 3600_000L; // 1.5 days into anomaly window
            log.info("    {} — injecting {} cross-channel txns to same beneficiary (Rule 13)",
                    clientId, crossCount);
            for (int i = 0; i < crossCount; i++) {
                String txnId = clientId + "-XCHAN-" + String.format("%03d", i);
                String txnType = txnTypes.get(i); // different type each time
                // Each individually normal but total to this beneficiary is large
                double amount = avgAmount * (1.5 + random.nextDouble() * 0.5);
                amount = Math.round(amount * 100.0) / 100.0;
                long ts = crossDayStart + (long) (i * 3600_000L + random.nextDouble() * 1800_000);
                writeTransaction(txnId, clientId, txnType, amount, ts, crossAcct, crossIfsc);
                txnCount++;
                anomalyCount++;
            }
        }

        int total = counter.addAndGet(txnCount);
        log.info("  {} — {} txns seeded (anomalies: {}). Running total: {}",
                clientId, txnCount, anomalyCount, total);
    }

    /**
     * Seed shared mule-like beneficiary patterns across CLIENT-007, CLIENT-008, CLIENT-009.
     *
     * 7 shared "mule" beneficiary accounts. Each of the three clients sends 10-20
     * transactions to each shared mule beneficiary, creating fan-in=3 per mule bene,
     * high shared beneficiary ratio, and full network density (all 3 clients interconnected).
     */
    private void seedMuleNetworkPatterns(Instant end, AtomicInteger counter) {
        log.info("  Seeding mule network patterns for CLIENT-007, CLIENT-008, CLIENT-009...");

        String[][] muleBeneficiaries = {
                {"HDFC0007001", "7001000001"},
                {"ICIC0007002", "7002000002"},
                {"SBIN0007003", "7003000003"},
                {"UTIB0007004", "7004000004"},
                {"KKBK0007005", "7005000005"},
                {"PUNB0007006", "7006000006"},
                {"BARB0007007", "7007000007"}
        };

        String[] muleClients = {"CLIENT-007", "CLIENT-008", "CLIENT-009"};

        long windowStart = end.minus(10, ChronoUnit.DAYS).toEpochMilli();
        long windowEnd = end.toEpochMilli();
        int txnCount = 0;

        for (String clientId : muleClients) {
            for (int b = 0; b < muleBeneficiaries.length; b++) {
                String beneIfsc = muleBeneficiaries[b][0];
                String beneAcct = muleBeneficiaries[b][1];

                int txnsToThisBene = 10 + random.nextInt(11); // 10-20 txns
                for (int i = 0; i < txnsToThisBene; i++) {
                    String txnId = clientId + "-MULE-B" + b + "-" + String.format("%03d", i);
                    double amount = 20_000 + random.nextDouble() * 80_000; // 20K-100K
                    amount = Math.round(amount * 100.0) / 100.0;
                    long ts = windowStart + (long) (random.nextDouble() * (windowEnd - windowStart));
                    writeTransaction(txnId, clientId, "NEFT", amount, ts, beneAcct, beneIfsc);
                    txnCount++;
                }
            }
        }

        int total = counter.addAndGet(txnCount);
        log.info("  Mule network: {} txns across 3 clients and 7 shared beneficiaries. Running total: {}",
                txnCount, total);
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

        // Use configurable txn types from config
        List<String> allTypes = thresholdConfig.getTransactionTypes();

        // Find types NOT in the normal pool
        java.util.List<String> unusual = new java.util.ArrayList<>();
        for (String type : allTypes) {
            if (!normalTypes.contains(type)) {
                unusual.add(type);
            }
        }

        if (unusual.isEmpty()) {
            return allTypes.get(random.nextInt(allTypes.size()));
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
