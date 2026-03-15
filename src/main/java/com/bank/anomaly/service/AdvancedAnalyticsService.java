package com.bank.anomaly.service;

import com.bank.anomaly.model.*;
import com.bank.anomaly.repository.ClientProfileRepository;
import com.bank.anomaly.repository.ReviewQueueRepository;
import com.bank.anomaly.repository.RiskResultRepository;
import com.bank.anomaly.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class AdvancedAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AdvancedAnalyticsService.class);

    private final RiskResultRepository riskResultRepository;
    private final ClientProfileRepository clientProfileRepository;
    private final ReviewQueueRepository reviewQueueRepository;
    private final TransactionRepository transactionRepository;
    private final BeneficiaryGraphService graphService;
    private final SilenceDetectionService silenceDetectionService;
    private final OllamaService ollamaService;
    private final AnalyticsService analyticsService;

    public AdvancedAnalyticsService(RiskResultRepository riskResultRepository,
                                    ClientProfileRepository clientProfileRepository,
                                    ReviewQueueRepository reviewQueueRepository,
                                    TransactionRepository transactionRepository,
                                    BeneficiaryGraphService graphService,
                                    SilenceDetectionService silenceDetectionService,
                                    OllamaService ollamaService,
                                    AnalyticsService analyticsService) {
        this.riskResultRepository = riskResultRepository;
        this.clientProfileRepository = clientProfileRepository;
        this.reviewQueueRepository = reviewQueueRepository;
        this.transactionRepository = transactionRepository;
        this.graphService = graphService;
        this.silenceDetectionService = silenceDetectionService;
        this.ollamaService = ollamaService;
        this.analyticsService = analyticsService;
    }

    // ───────────────────────────────────────────────────────────
    // TOP RISK CLIENTS
    // ───────────────────────────────────────────────────────────

    public List<Map<String, Object>> getTopRiskClients(int limit, String sortBy) {
        List<ClientProfile> profiles = clientProfileRepository.scanAllProfiles();
        if (profiles.isEmpty()) return Collections.emptyList();

        // For each client, gather aggregate stats from recent evaluations
        List<Map<String, Object>> clientStats = new ArrayList<>();

        for (ClientProfile profile : profiles) {
            PagedResponse<EvaluationResult> evals = riskResultRepository.findByClientId(
                    profile.getClientId(), 100, null);
            List<EvaluationResult> evalList = evals != null ? evals.data() : List.of();
            if (evalList.isEmpty()) continue;

            double avgScore = evalList.stream()
                    .mapToDouble(EvaluationResult::getCompositeScore).average().orElse(0);
            double maxScore = evalList.stream()
                    .mapToDouble(EvaluationResult::getCompositeScore).max().orElse(0);
            long alertCount = evalList.stream()
                    .filter(e -> "ALERT".equals(e.getAction())).count();
            long blockCount = evalList.stream()
                    .filter(e -> "BLOCK".equals(e.getAction())).count();

            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("clientId", profile.getClientId());
            stats.put("avgScore", Math.round(avgScore * 100.0) / 100.0);
            stats.put("maxScore", Math.round(maxScore * 100.0) / 100.0);
            stats.put("alertCount", alertCount);
            stats.put("blockCount", blockCount);
            stats.put("totalEvaluated", evalList.size());
            stats.put("totalTransactions", profile.getTotalTxnCount());
            clientStats.add(stats);
        }

        // Sort
        Comparator<Map<String, Object>> comparator = switch (sortBy != null ? sortBy : "avgScore") {
            case "maxScore" -> Comparator.comparingDouble(
                    (Map<String, Object> m) -> ((Number) m.get("maxScore")).doubleValue()).reversed();
            case "blockCount" -> Comparator.comparingLong(
                    (Map<String, Object> m) -> ((Number) m.get("blockCount")).longValue()).reversed();
            case "alertCount" -> Comparator.comparingLong(
                    (Map<String, Object> m) -> ((Number) m.get("alertCount")).longValue()).reversed();
            default -> Comparator.comparingDouble(
                    (Map<String, Object> m) -> ((Number) m.get("avgScore")).doubleValue()).reversed();
        };

        clientStats.sort(comparator);
        return clientStats.subList(0, Math.min(limit, clientStats.size()));
    }

    // ───────────────────────────────────────────────────────────
    // SYSTEM OVERVIEW
    // ───────────────────────────────────────────────────────────

    public Map<String, Object> getSystemOverview() {
        Map<String, Object> overview = new LinkedHashMap<>();

        // Profile stats
        List<ClientProfile> profiles = clientProfileRepository.scanAllProfiles();
        overview.put("totalClients", profiles.size());
        long totalTxns = profiles.stream().mapToLong(ClientProfile::getTotalTxnCount).sum();
        overview.put("totalTransactions", totalTxns);

        // Review queue stats — int[]: [pending, truePositive, falsePositive, autoAccepted]
        int[] queueCounts = reviewQueueRepository.countByStatus(null, null);
        Map<String, Integer> queueStats = new LinkedHashMap<>();
        queueStats.put("pending", queueCounts[0]);
        queueStats.put("truePositive", queueCounts[1]);
        queueStats.put("falsePositive", queueCounts[2]);
        queueStats.put("autoAccepted", queueCounts[3]);
        overview.put("reviewQueue", queueStats);

        // Silent clients
        overview.put("silentClients", silenceDetectionService.getAlertedClients().size());

        // Beneficiary graph
        Map<String, Object> graphStats = new LinkedHashMap<>();
        graphStats.put("ready", graphService.isGraphReady());
        graphStats.put("totalBeneficiaries", graphService.getTotalBeneficiaryKeys());
        graphStats.put("totalClients", graphService.getTotalClientCount());
        graphStats.put("lastRefresh", graphService.getLastRefreshTime().toString());
        overview.put("beneficiaryGraph", graphStats);

        return overview;
    }

    // ───────────────────────────────────────────────────────────
    // SEARCH TRANSACTIONS
    // ───────────────────────────────────────────────────────────

    public List<Map<String, Object>> searchTransactions(Long fromDate, Long toDate,
                                                         String clientId, String txnType,
                                                         Double minAmount, Double maxAmount,
                                                         String beneficiaryAccount,
                                                         int limit) {
        long from = fromDate != null ? fromDate : 0;
        long to = toDate != null ? toDate : System.currentTimeMillis();

        List<Transaction> txns = transactionRepository.findByTimeRange(from, to, txnType, 5000);

        return txns.stream()
                .filter(t -> clientId == null || clientId.equalsIgnoreCase(t.getClientId()))
                .filter(t -> minAmount == null || t.getAmount() >= minAmount)
                .filter(t -> maxAmount == null || t.getAmount() <= maxAmount)
                .filter(t -> beneficiaryAccount == null ||
                        beneficiaryAccount.equals(t.getBeneficiaryAccount()))
                .sorted(Comparator.comparingLong(Transaction::getTimestamp).reversed())
                .limit(limit)
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("txnId", t.getTxnId());
                    m.put("clientId", t.getClientId());
                    m.put("txnType", t.getTxnType());
                    m.put("amount", t.getAmount());
                    m.put("timestamp", t.getTimestamp());
                    if (t.getBeneficiaryAccount() != null) m.put("beneficiaryAccount", t.getBeneficiaryAccount());
                    if (t.getBeneficiaryIfsc() != null) m.put("beneficiaryIfsc", t.getBeneficiaryIfsc());
                    return m;
                })
                .collect(Collectors.toList());
    }

    // ───────────────────────────────────────────────────────────
    // SIMULATE TRANSACTION (dry-run)
    // ───────────────────────────────────────────────────────────
    // Handled in controller by delegating to evaluate without persisting — see below

    // ───────────────────────────────────────────────────────────
    // ANOMALY TRENDS
    // ───────────────────────────────────────────────────────────

    public Map<String, Object> getAnomalyTrends(Long fromDate, Long toDate, String bucketSize) {
        long from = fromDate != null ? fromDate : System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
        long to = toDate != null ? toDate : System.currentTimeMillis();

        long bucketMs = switch (bucketSize != null ? bucketSize : "1h") {
            case "15m" -> 15L * 60 * 1000;
            case "1h" -> 60L * 60 * 1000;
            case "6h" -> 6L * 60 * 60 * 1000;
            case "1d" -> 24L * 60 * 60 * 1000;
            default -> 60L * 60 * 1000;
        };

        List<EvaluationResult> results = riskResultRepository.findByTimeRange(from, to, null, null, 10000);

        // Bucket the results
        Map<Long, List<EvaluationResult>> buckets = new TreeMap<>();
        for (EvaluationResult r : results) {
            long bucket = (r.getEvaluatedAt() / bucketMs) * bucketMs;
            buckets.computeIfAbsent(bucket, k -> new ArrayList<>()).add(r);
        }

        List<Map<String, Object>> trend = new ArrayList<>();
        for (Map.Entry<Long, List<EvaluationResult>> entry : buckets.entrySet()) {
            List<EvaluationResult> bucket = entry.getValue();
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("timestamp", entry.getKey());
            point.put("total", bucket.size());
            point.put("passCount", bucket.stream().filter(e -> "PASS".equals(e.getAction())).count());
            point.put("alertCount", bucket.stream().filter(e -> "ALERT".equals(e.getAction())).count());
            point.put("blockCount", bucket.stream().filter(e -> "BLOCK".equals(e.getAction())).count());
            point.put("avgScore", Math.round(bucket.stream()
                    .mapToDouble(EvaluationResult::getCompositeScore).average().orElse(0) * 100.0) / 100.0);
            trend.add(point);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fromDate", from);
        response.put("toDate", to);
        response.put("bucketSize", bucketSize != null ? bucketSize : "1h");
        response.put("bucketCount", trend.size());
        response.put("trend", trend);

        // Summary
        long totalAlerts = results.stream().filter(e -> "ALERT".equals(e.getAction())).count();
        long totalBlocks = results.stream().filter(e -> "BLOCK".equals(e.getAction())).count();
        response.put("totalAlerts", totalAlerts);
        response.put("totalBlocks", totalBlocks);
        response.put("totalEvaluated", results.size());

        return response;
    }

    // ───────────────────────────────────────────────────────────
    // MULE CANDIDATES
    // ───────────────────────────────────────────────────────────

    public List<Map<String, Object>> getMuleCandidates(int limit, int minFanIn) {
        if (!graphService.isGraphReady()) {
            return Collections.emptyList();
        }

        // Scan all beneficiary keys and rank by fan-in
        List<Map<String, Object>> candidates = new ArrayList<>();

        // We need to iterate the graph's beneficiary-to-senders map
        // The graph service exposes per-key methods; we'll use profile data
        List<ClientProfile> profiles = clientProfileRepository.scanAllProfiles();
        Map<String, Set<String>> beneToSenders = new HashMap<>();

        for (ClientProfile p : profiles) {
            if (p.getBeneficiaryTxnCounts() == null) continue;
            for (String beneKey : p.getBeneficiaryTxnCounts().keySet()) {
                beneToSenders.computeIfAbsent(beneKey, k -> new HashSet<>()).add(p.getClientId());
            }
        }

        for (Map.Entry<String, Set<String>> entry : beneToSenders.entrySet()) {
            int fanIn = entry.getValue().size();
            if (fanIn < minFanIn) continue;

            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("beneficiaryKey", entry.getKey());
            candidate.put("fanIn", fanIn);
            candidate.put("senders", new ArrayList<>(entry.getValue()));
            candidates.add(candidate);
        }

        candidates.sort(Comparator.comparingInt(
                (Map<String, Object> m) -> ((Number) m.get("fanIn")).intValue()).reversed());

        return candidates.subList(0, Math.min(limit, candidates.size()));
    }

    // ───────────────────────────────────────────────────────────
    // INVESTIGATION REPORT
    // ───────────────────────────────────────────────────────────

    public Map<String, Object> generateInvestigationReport(String clientId) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("clientId", clientId);
        report.put("generatedAt", System.currentTimeMillis());

        // 1. Client profile
        ClientProfile profile = clientProfileRepository.findByClientId(clientId);
        if (profile == null) {
            report.put("error", "Client not found");
            return report;
        }

        Map<String, Object> profileSummary = new LinkedHashMap<>();
        profileSummary.put("totalTransactions", profile.getTotalTxnCount());
        profileSummary.put("ewmaAmount", Math.round(profile.getEwmaAmount() * 100.0) / 100.0);
        profileSummary.put("ewmaHourlyTps", Math.round(profile.getEwmaHourlyTps() * 1000.0) / 1000.0);
        profileSummary.put("txnTypeCounts", profile.getTxnTypeCounts());
        report.put("profile", profileSummary);

        // 2. Recent evaluations
        PagedResponse<EvaluationResult> evals = riskResultRepository.findByClientId(clientId, 50, null);
        List<EvaluationResult> evalList = evals != null ? evals.data() : List.of();

        long alerts = evalList.stream().filter(e -> "ALERT".equals(e.getAction())).count();
        long blocks = evalList.stream().filter(e -> "BLOCK".equals(e.getAction())).count();
        double avgScore = evalList.stream().mapToDouble(EvaluationResult::getCompositeScore).average().orElse(0);

        Map<String, Object> evalSummary = new LinkedHashMap<>();
        evalSummary.put("totalEvaluated", evalList.size());
        evalSummary.put("alerts", alerts);
        evalSummary.put("blocks", blocks);
        evalSummary.put("avgCompositeScore", Math.round(avgScore * 100.0) / 100.0);
        report.put("evaluationSummary", evalSummary);

        // 3. Most triggered rules
        Map<String, Integer> ruleTriggeredCounts = new HashMap<>();
        for (EvaluationResult eval : evalList) {
            if (eval.getRuleResults() == null) continue;
            for (RuleResult rr : eval.getRuleResults()) {
                if (rr.isTriggered()) {
                    ruleTriggeredCounts.merge(rr.getRuleId(), 1, Integer::sum);
                }
            }
        }
        List<Map<String, Object>> topRules = ruleTriggeredCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ruleId", e.getKey());
                    m.put("triggerCount", e.getValue());
                    return m;
                })
                .collect(Collectors.toList());
        report.put("topTriggeredRules", topRules);

        // 4. Beneficiary network
        Map<String, Object> networkInfo = new LinkedHashMap<>();
        networkInfo.put("totalBeneficiaries", graphService.getTotalBeneficiaryCount(clientId));
        networkInfo.put("sharedBeneficiaries", graphService.getSharedBeneficiaryCount(clientId));
        networkInfo.put("networkDensity", Math.round(graphService.getNetworkDensity(clientId) * 1000.0) / 1000.0);
        report.put("beneficiaryNetwork", networkInfo);

        // 5. AI narrative
        try {
            String narrative = ollamaService.generateClientNarrative(profile, evalList);
            report.put("aiNarrative", narrative != null ? narrative : "AI service unavailable");
        } catch (Exception e) {
            report.put("aiNarrative", "Failed to generate narrative: " + e.getMessage());
        }

        return report;
    }

    // ───────────────────────────────────────────────────────────
    // RULE CORRELATIONS
    // ───────────────────────────────────────────────────────────

    public Map<String, Object> getRuleCorrelations(Long fromDate, Long toDate) {
        long from = fromDate != null ? fromDate : 0;
        long to = toDate != null ? toDate : System.currentTimeMillis();

        List<EvaluationResult> results = riskResultRepository.findByTimeRange(from, to, null, null, 10000);

        // Build co-occurrence matrix
        Map<String, Map<String, Integer>> coOccurrence = new HashMap<>();
        Map<String, Integer> ruleTriggerCounts = new HashMap<>();

        for (EvaluationResult eval : results) {
            if (eval.getRuleResults() == null) continue;
            List<String> triggered = eval.getRuleResults().stream()
                    .filter(RuleResult::isTriggered)
                    .map(RuleResult::getRuleId)
                    .collect(Collectors.toList());

            for (String ruleId : triggered) {
                ruleTriggerCounts.merge(ruleId, 1, Integer::sum);
            }

            // Pairwise co-occurrence
            for (int i = 0; i < triggered.size(); i++) {
                for (int j = i + 1; j < triggered.size(); j++) {
                    String a = triggered.get(i);
                    String b = triggered.get(j);
                    String key = a.compareTo(b) < 0 ? a : b;
                    String val = a.compareTo(b) < 0 ? b : a;

                    coOccurrence.computeIfAbsent(key, k -> new HashMap<>())
                            .merge(val, 1, Integer::sum);
                }
            }
        }

        // Build pairs list sorted by co-occurrence count
        List<Map<String, Object>> pairs = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> outer : coOccurrence.entrySet()) {
            for (Map.Entry<String, Integer> inner : outer.getValue().entrySet()) {
                int countA = ruleTriggerCounts.getOrDefault(outer.getKey(), 0);
                int countB = ruleTriggerCounts.getOrDefault(inner.getKey(), 0);
                int coCount = inner.getValue();
                double jaccardIndex = (countA + countB - coCount) > 0
                        ? (double) coCount / (countA + countB - coCount) : 0;

                Map<String, Object> pair = new LinkedHashMap<>();
                pair.put("ruleA", outer.getKey());
                pair.put("ruleB", inner.getKey());
                pair.put("coOccurrenceCount", coCount);
                pair.put("ruleATriggers", countA);
                pair.put("ruleBTriggers", countB);
                pair.put("jaccardIndex", Math.round(jaccardIndex * 1000.0) / 1000.0);
                pairs.add(pair);
            }
        }

        pairs.sort(Comparator.comparingInt(
                (Map<String, Object> m) -> ((Number) m.get("coOccurrenceCount")).intValue()).reversed());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalEvaluationsAnalyzed", results.size());
        response.put("uniqueRulesTriggered", ruleTriggerCounts.size());
        response.put("correlationPairs", pairs);
        response.put("ruleTriggerCounts", ruleTriggerCounts);
        return response;
    }
}
