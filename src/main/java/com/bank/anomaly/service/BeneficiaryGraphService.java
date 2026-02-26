package com.bank.anomaly.service;

import com.bank.anomaly.model.ClientProfile;
import com.bank.anomaly.repository.ClientProfileRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * In-memory bipartite graph mapping beneficiaries to the clients that send to them.
 * Used by MuleNetworkEvaluator to detect shared-beneficiary patterns indicative of
 * mule networks (fan-in convergence, ring patterns, hub-and-spoke).
 *
 * The graph is rebuilt on a schedule from existing ClientProfile data — no new
 * Aerospike schema is required.
 */
@Service
public class BeneficiaryGraphService {

    private static final Logger log = LoggerFactory.getLogger(BeneficiaryGraphService.class);

    private final ClientProfileRepository clientProfileRepository;

    // Primary index: beneficiary key -> set of client IDs that send to it
    private volatile Map<String, Set<String>> beneficiaryToSenders = Collections.emptyMap();

    // Secondary index: client ID -> set of beneficiary keys
    private volatile Map<String, Set<String>> clientToBeneficiaries = Collections.emptyMap();

    // Pre-computed: per client, count of beneficiaries shared with at least one other client
    private volatile Map<String, Integer> clientSharedBeneficiaryCount = Collections.emptyMap();

    // Pre-computed: per client, network density among neighbor clients (0.0 - 1.0)
    private volatile Map<String, Double> clientNetworkDensity = Collections.emptyMap();

    private volatile Instant lastRefreshTime = Instant.EPOCH;

    public BeneficiaryGraphService(ClientProfileRepository clientProfileRepository) {
        this.clientProfileRepository = clientProfileRepository;
    }

    @PostConstruct
    public void init() {
        refreshGraph();
    }

    @Scheduled(fixedDelayString = "${risk.rule-defaults.mule-graph-refresh-ms:300000}")
    public void refreshGraph() {
        log.info("Refreshing beneficiary graph...");
        Instant start = Instant.now();

        List<ClientProfile> allProfiles;
        try {
            allProfiles = clientProfileRepository.scanAllProfiles();
        } catch (Exception e) {
            log.warn("Failed to scan profiles for graph build: {}", e.getMessage());
            return;
        }

        if (allProfiles.isEmpty()) {
            log.info("No profiles found — graph remains empty");
            lastRefreshTime = Instant.now();
            return;
        }

        // Step 1: Build bipartite graph
        Map<String, Set<String>> newBeneToSenders = new HashMap<>();
        Map<String, Set<String>> newClientToBene = new HashMap<>();

        for (ClientProfile profile : allProfiles) {
            String clientId = profile.getClientId();
            Set<String> beneKeys = profile.getBeneficiaryTxnCounts().keySet();
            if (beneKeys.isEmpty()) continue;

            newClientToBene.put(clientId, new HashSet<>(beneKeys));

            for (String beneKey : beneKeys) {
                newBeneToSenders
                        .computeIfAbsent(beneKey, k -> new HashSet<>())
                        .add(clientId);
            }
        }

        // Step 2: Pre-compute shared beneficiary counts per client
        Map<String, Integer> newSharedCounts = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : newClientToBene.entrySet()) {
            String clientId = entry.getKey();
            int sharedCount = 0;
            for (String beneKey : entry.getValue()) {
                Set<String> senders = newBeneToSenders.getOrDefault(beneKey, Collections.emptySet());
                if (senders.size() > 1) {
                    sharedCount++;
                }
            }
            newSharedCounts.put(clientId, sharedCount);
        }

        // Step 3: Pre-compute network density per client
        Map<String, Double> newDensity = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : newClientToBene.entrySet()) {
            String clientId = entry.getKey();

            // Collect all OTHER clients that share at least one beneficiary
            Set<String> neighborClients = new HashSet<>();
            for (String beneKey : entry.getValue()) {
                Set<String> senders = newBeneToSenders.getOrDefault(beneKey, Collections.emptySet());
                for (String sender : senders) {
                    if (!sender.equals(clientId)) {
                        neighborClients.add(sender);
                    }
                }
            }

            if (neighborClients.size() < 2) {
                newDensity.put(clientId, 0.0);
                continue;
            }

            // Density = fraction of neighbor pairs that also share a beneficiary
            // Cap at 50 neighbors for efficiency
            List<String> neighborList = new ArrayList<>(neighborClients);
            if (neighborList.size() > 50) {
                neighborList = neighborList.subList(0, 50);
            }

            int pairs = 0;
            int connectedPairs = 0;
            for (int i = 0; i < neighborList.size(); i++) {
                Set<String> beneI = newClientToBene.getOrDefault(neighborList.get(i), Collections.emptySet());
                for (int j = i + 1; j < neighborList.size(); j++) {
                    pairs++;
                    Set<String> beneJ = newClientToBene.getOrDefault(neighborList.get(j), Collections.emptySet());
                    for (String b : beneI) {
                        if (beneJ.contains(b)) {
                            connectedPairs++;
                            break;
                        }
                    }
                }
            }
            double density = pairs > 0 ? (double) connectedPairs / pairs : 0.0;
            newDensity.put(clientId, density);
        }

        // Step 4: Atomic swap
        this.beneficiaryToSenders = newBeneToSenders;
        this.clientToBeneficiaries = newClientToBene;
        this.clientSharedBeneficiaryCount = newSharedCounts;
        this.clientNetworkDensity = newDensity;
        this.lastRefreshTime = Instant.now();

        log.info("Beneficiary graph refreshed: {} beneficiaries, {} clients, took {}ms",
                newBeneToSenders.size(), newClientToBene.size(),
                Duration.between(start, Instant.now()).toMillis());
    }

    /** Number of distinct clients sending to this beneficiary. */
    public int getFanInCount(String beneficiaryKey) {
        return beneficiaryToSenders.getOrDefault(beneficiaryKey, Collections.emptySet()).size();
    }

    /** Count of this client's beneficiaries that are shared with at least one other client. */
    public int getSharedBeneficiaryCount(String clientId) {
        return clientSharedBeneficiaryCount.getOrDefault(clientId, 0);
    }

    /** Total distinct beneficiaries for this client (from graph snapshot). */
    public int getTotalBeneficiaryCount(String clientId) {
        return clientToBeneficiaries.getOrDefault(clientId, Collections.emptySet()).size();
    }

    /** Network density among clients sharing beneficiaries with this client (0.0–1.0). */
    public double getNetworkDensity(String clientId) {
        return clientNetworkDensity.getOrDefault(clientId, 0.0);
    }

    /** Other client IDs that also send to this beneficiary (excludes the given client). */
    public Set<String> getOtherSenders(String beneficiaryKey, String excludeClientId) {
        Set<String> senders = beneficiaryToSenders.getOrDefault(beneficiaryKey, Collections.emptySet());
        Set<String> result = new HashSet<>(senders);
        result.remove(excludeClientId);
        return result;
    }

    /** Whether the graph has been built at least once. */
    public boolean isGraphReady() {
        return !lastRefreshTime.equals(Instant.EPOCH);
    }

    public Instant getLastRefreshTime() {
        return lastRefreshTime;
    }

    public int getTotalBeneficiaryKeys() {
        return beneficiaryToSenders.size();
    }

    public int getTotalClientCount() {
        return clientToBeneficiaries.size();
    }

    /** All beneficiary keys for a given client (from graph snapshot). */
    public Set<String> getBeneficiariesForClient(String clientId) {
        return clientToBeneficiaries.getOrDefault(clientId, Collections.emptySet());
    }
}
