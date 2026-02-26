package com.bank.anomaly.service;

import com.bank.anomaly.model.*;
import com.bank.anomaly.repository.ReviewQueueRepository;
import com.bank.anomaly.repository.RuleRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class AnalyticsService {

    private final ReviewQueueRepository reviewQueueRepository;
    private final RuleRepository ruleRepository;
    private final BeneficiaryGraphService graphService;

    public AnalyticsService(ReviewQueueRepository reviewQueueRepository,
                            RuleRepository ruleRepository,
                            BeneficiaryGraphService graphService) {
        this.reviewQueueRepository = reviewQueueRepository;
        this.ruleRepository = ruleRepository;
        this.graphService = graphService;
    }

    /**
     * Compute per-rule performance stats from review queue feedback.
     * Uses triggeredRuleIds stored on each ReviewQueueItem.
     */
    public List<RulePerformance> getRulePerformanceStats() {
        // Build rule lookup from cached rules
        Map<String, AnomalyRule> ruleMap = ruleRepository.getAllRulesCached().stream()
                .collect(Collectors.toMap(AnomalyRule::getRuleId, r -> r, (a, b) -> a));

        // Get all items with explicit feedback (TP or FP)
        List<ReviewQueueItem> feedbackItems = reviewQueueRepository.findAllWithFeedback();

        // Aggregate per-rule TP/FP counts
        Map<String, int[]> ruleCounts = new HashMap<>(); // ruleId -> [trigger, tp, fp]
        for (ReviewQueueItem item : feedbackItems) {
            if (item.getTriggeredRuleIds() == null) continue;
            boolean isTp = item.getFeedbackStatus() == ReviewStatus.TRUE_POSITIVE;
            boolean isFp = item.getFeedbackStatus() == ReviewStatus.FALSE_POSITIVE;

            for (String ruleId : item.getTriggeredRuleIds()) {
                int[] counts = ruleCounts.computeIfAbsent(ruleId, k -> new int[3]);
                counts[0]++; // trigger
                if (isTp) counts[1]++;
                if (isFp) counts[2]++;
            }
        }

        // Build result for all rules (even those with no feedback yet)
        List<RulePerformance> results = new ArrayList<>();
        for (AnomalyRule rule : ruleMap.values()) {
            int[] counts = ruleCounts.getOrDefault(rule.getRuleId(), new int[3]);
            int total = counts[1] + counts[2];
            double precision = total > 0 ? (double) counts[1] / total : 0.0;

            results.add(RulePerformance.builder()
                    .ruleId(rule.getRuleId())
                    .ruleName(rule.getName())
                    .ruleType(rule.getRuleType() != null ? rule.getRuleType().name() : "")
                    .currentWeight(rule.getRiskWeight())
                    .triggerCount(counts[0])
                    .tpCount(counts[1])
                    .fpCount(counts[2])
                    .precision(Math.round(precision * 1000.0) / 1000.0)
                    .build());
        }

        // Sort by trigger count descending
        results.sort((a, b) -> Integer.compare(b.getTriggerCount(), a.getTriggerCount()));
        return results;
    }

    /**
     * Build a network graph for visualization centered on a client.
     * Returns the client's beneficiaries, co-senders, and edges.
     */
    public NetworkGraph getClientNetwork(String clientId) {
        if (!graphService.isGraphReady()) {
            return NetworkGraph.builder()
                    .nodes(Collections.emptyList())
                    .edges(Collections.emptyList())
                    .build();
        }

        Set<String> beneficiaries = graphService.getBeneficiariesForClient(clientId);
        if (beneficiaries.isEmpty()) {
            return NetworkGraph.builder()
                    .nodes(List.of(NetworkGraph.NetworkNode.builder()
                            .id(clientId).label(clientId).type("CLIENT").isCenter(true).build()))
                    .edges(Collections.emptyList())
                    .build();
        }

        List<NetworkGraph.NetworkNode> nodes = new ArrayList<>();
        List<NetworkGraph.NetworkEdge> edges = new ArrayList<>();
        Set<String> addedClients = new HashSet<>();

        // Center client node
        nodes.add(NetworkGraph.NetworkNode.builder()
                .id(clientId).label(clientId).type("CLIENT").isCenter(true).build());
        addedClients.add(clientId);

        // Cap beneficiary nodes for performance
        int beneLimit = Math.min(beneficiaries.size(), 50);
        int beneCount = 0;

        for (String beneKey : beneficiaries) {
            if (beneCount >= beneLimit) break;

            int fanIn = graphService.getFanInCount(beneKey);
            // Shorten label for display: HDFC0007001:7001000001 -> ...7001000001
            String shortLabel = beneKey.length() > 15
                    ? "..." + beneKey.substring(beneKey.indexOf(':') + 1)
                    : beneKey;

            nodes.add(NetworkGraph.NetworkNode.builder()
                    .id(beneKey).label(shortLabel).type("BENEFICIARY").fanIn(fanIn).build());
            edges.add(NetworkGraph.NetworkEdge.builder()
                    .from(clientId).to(beneKey).build());

            // Add other senders (neighbor clients)
            Set<String> otherSenders = graphService.getOtherSenders(beneKey, clientId);
            for (String sender : otherSenders) {
                if (!addedClients.contains(sender)) {
                    nodes.add(NetworkGraph.NetworkNode.builder()
                            .id(sender).label(sender).type("CLIENT").isCenter(false).build());
                    addedClients.add(sender);
                }
                edges.add(NetworkGraph.NetworkEdge.builder()
                        .from(sender).to(beneKey).build());
            }

            beneCount++;
        }

        return NetworkGraph.builder().nodes(nodes).edges(edges).build();
    }
}
