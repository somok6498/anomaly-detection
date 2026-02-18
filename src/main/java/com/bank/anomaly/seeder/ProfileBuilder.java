package com.bank.anomaly.seeder;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.policy.ScanPolicy;
import com.bank.anomaly.config.AerospikeConfig;
import com.bank.anomaly.model.ClientProfile;
import com.bank.anomaly.model.Transaction;
import com.bank.anomaly.service.IsolationForestTrainingService;
import com.bank.anomaly.service.ProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scans all historical transactions and builds client behavioral profiles.
 * Runs after DataSeeder (via @Order) only when the "seed" profile is active.
 *
 * This processes transactions in chronological order per client so EWMA
 * stats accurately reflect the historical progression.
 */
@Component
@Profile("seed")
@Order(2)  // run after DataSeeder (@Order default is MAX)
public class ProfileBuilder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ProfileBuilder.class);

    private final AerospikeClient aerospikeClient;
    private final String namespace;
    private final ProfileService profileService;
    private final IsolationForestTrainingService ifTrainingService;

    public ProfileBuilder(AerospikeClient aerospikeClient,
                          @Qualifier("aerospikeNamespace") String namespace,
                          ProfileService profileService,
                          IsolationForestTrainingService ifTrainingService) {
        this.aerospikeClient = aerospikeClient;
        this.namespace = namespace;
        this.profileService = profileService;
        this.ifTrainingService = ifTrainingService;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("=== Building client profiles from historical data ===");

        // Step 1: Scan all transactions and group by clientId
        Map<String, List<Transaction>> txnsByClient = new ConcurrentHashMap<>();
        AtomicInteger scanCount = new AtomicInteger(0);

        ScanPolicy scanPolicy = new ScanPolicy();
        scanPolicy.concurrentNodes = true;
        scanPolicy.includeBinData = true;

        aerospikeClient.scanAll(scanPolicy, namespace, AerospikeConfig.SET_TRANSACTIONS,
                (key, record) -> {
                    try {
                        Transaction txn = Transaction.builder()
                                .txnId(record.getString("txnId"))
                                .clientId(record.getString("clientId"))
                                .txnType(record.getString("txnType"))
                                .amount(record.getDouble("amount"))
                                .timestamp(record.getLong("timestamp"))
                                .build();

                        txnsByClient.computeIfAbsent(txn.getClientId(), k -> Collections.synchronizedList(new ArrayList<>()))
                                .add(txn);

                        int count = scanCount.incrementAndGet();
                        if (count % 10000 == 0) {
                            log.info("  Scanned {} transactions...", count);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to read transaction: {}", e.getMessage());
                    }
                });

        log.info("Scanned {} total transactions for {} clients",
                scanCount.get(), txnsByClient.size());

        // Step 2: Sort each client's transactions by timestamp and process in order
        for (Map.Entry<String, List<Transaction>> entry : txnsByClient.entrySet()) {
            String clientId = entry.getKey();
            List<Transaction> txns = entry.getValue();

            // Sort chronologically
            txns.sort(Comparator.comparingLong(Transaction::getTimestamp));

            ClientProfile profile = profileService.getOrCreateProfile(clientId);

            for (Transaction txn : txns) {
                profileService.updateProfile(profile, txn);
            }

            log.info("  Built profile for {}: {} txns, EWMA amount={}, EWMA hourly TPS={}, types={}",
                    clientId, txns.size(),
                    String.format("%.2f", profile.getEwmaAmount()),
                    String.format("%.2f", profile.getEwmaHourlyTps()),
                    profile.getTxnTypeCounts().keySet());
        }

        log.info("=== Profile building complete for {} clients ===", txnsByClient.size());

        // Step 3: Train Isolation Forest models for all clients
        log.info("=== Training Isolation Forest models ===");
        List<String> clientIds = new ArrayList<>(txnsByClient.keySet());
        ifTrainingService.trainForClients(clientIds, 100, 256);
        log.info("=== Isolation Forest training complete ===");
    }
}
