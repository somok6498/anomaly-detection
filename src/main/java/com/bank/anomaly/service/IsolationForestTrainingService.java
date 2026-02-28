package com.bank.anomaly.service;

import com.bank.anomaly.engine.isolationforest.FeatureExtractor;
import com.bank.anomaly.engine.isolationforest.IsolationForest;
import com.bank.anomaly.model.ClientProfile;
import com.bank.anomaly.model.Transaction;
import com.bank.anomaly.repository.IsolationForestModelRepository;
import com.bank.anomaly.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class IsolationForestTrainingService {

    private static final Logger log = LoggerFactory.getLogger(IsolationForestTrainingService.class);

    private final TransactionRepository transactionRepository;
    private final ProfileService profileService;
    private final IsolationForestModelRepository modelRepository;

    public IsolationForestTrainingService(TransactionRepository transactionRepository,
                                          ProfileService profileService,
                                          IsolationForestModelRepository modelRepository) {
        this.transactionRepository = transactionRepository;
        this.profileService = profileService;
        this.modelRepository = modelRepository;
    }

    /**
     * Train an Isolation Forest model for a specific client.
     * Scans the client's historical transactions, extracts feature vectors, and trains the forest.
     */
    public void trainForClient(String clientId, int numTrees, int sampleSize) {
        log.info("Training IF model for {}...", clientId);

        ClientProfile profile = profileService.getOrCreateProfile(clientId);
        if (profile.getTotalTxnCount() < 50) {
            log.warn("Client {} has insufficient history ({} txns). Skipping IF training.",
                    clientId, profile.getTotalTxnCount());
            return;
        }

        // Load all transactions for this client (no cursor — fetch all)
        List<Transaction> txns = transactionRepository.findByClientId(clientId, 10000, null).data();
        if (txns.isEmpty()) {
            log.warn("No transactions found for {}. Skipping IF training.", clientId);
            return;
        }

        // Extract feature vectors with simulated context jitter
        Random random = new Random(clientId.hashCode());
        List<double[]> featureVectors = new ArrayList<>();

        for (Transaction txn : txns) {
            double tpsJitter = (random.nextGaussian() * 0.3); // ±30% jitter around baseline
            double amtJitter = (random.nextGaussian() * 0.3);
            double[] features = FeatureExtractor.extractForTraining(txn, profile, tpsJitter, amtJitter);
            featureVectors.add(features);
        }

        double[][] data = featureVectors.toArray(new double[0][]);

        // Train
        IsolationForest forest = new IsolationForest();
        forest.train(data, numTrees, sampleSize, clientId.hashCode());

        // Persist
        modelRepository.save(clientId, forest, data.length);

        log.info("Trained IF model for {}: {} trees, {} samples, {} features",
                clientId, numTrees, data.length, FeatureExtractor.FEATURE_COUNT);
    }

    /**
     * Train models for all clients that have profiles.
     */
    public void trainAll(int numTrees, int sampleSize) {
        log.info("=== Starting IF model training for all clients ===");

        // Get all unique clientIds from transactions — use hardcoded list for POC
        Set<String> clientIds = findAllClientIds();

        for (String clientId : clientIds) {
            try {
                trainForClient(clientId, numTrees, sampleSize);
            } catch (Exception e) {
                log.error("Failed to train IF model for {}", clientId, e);
            }
        }

        log.info("=== IF model training complete for {} clients ===", clientIds.size());
    }

    /**
     * Train for a known list of clients (used by seeder).
     */
    public void trainForClients(List<String> clientIds, int numTrees, int sampleSize) {
        log.info("Training IF models for {} clients...", clientIds.size());
        for (String clientId : clientIds) {
            try {
                trainForClient(clientId, numTrees, sampleSize);
            } catch (Exception e) {
                log.error("Failed to train IF model for {}", clientId, e);
            }
        }
        log.info("IF model training complete for {} clients", clientIds.size());
    }

    private Set<String> findAllClientIds() {
        // For the POC, we'll use the known client IDs
        return Set.of(
                "CLIENT-001", "CLIENT-002", "CLIENT-003", "CLIENT-004", "CLIENT-005",
                "CLIENT-006", "CLIENT-007", "CLIENT-008", "CLIENT-009", "CLIENT-010"
        );
    }
}
