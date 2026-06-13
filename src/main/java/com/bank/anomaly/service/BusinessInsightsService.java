package com.bank.anomaly.service;

import com.bank.anomaly.config.RiskThresholdConfig;
import com.bank.anomaly.model.ClientProfile;
import com.bank.anomaly.model.ClientSegment;
import com.bank.anomaly.model.EvaluationResult;
import com.bank.anomaly.model.PagedResponse;
import com.bank.anomaly.repository.ClientProfileRepository;
import com.bank.anomaly.repository.RiskResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class BusinessInsightsService {

    private static final Logger log = LoggerFactory.getLogger(BusinessInsightsService.class);

    private static final long DORMANCY_THRESHOLD_MS = 7L * 24 * 60 * 60 * 1000;
    private static final long NEW_CLIENT_TXN_THRESHOLD = 100;

    private static final Map<String, RailCharacteristics> RAIL_CHARACTERISTICS = Map.of(
            "UPI", new RailCharacteristics(0, 100_000, true, "Instant, zero-cost micro to mid-range payments"),
            "IMPS", new RailCharacteristics(0, 500_000, true, "Instant 24x7, small fee, mid-range transfers"),
            "NEFT", new RailCharacteristics(0, Double.MAX_VALUE, false, "Batch settlement (30-min cycles), no upper limit, low cost"),
            "RTGS", new RailCharacteristics(200_000, Double.MAX_VALUE, true, "Real-time gross settlement, high-value only"),
            "IFT", new RailCharacteristics(0, Double.MAX_VALUE, false, "Internal fund transfer, zero cost, intra-bank")
    );

    private final ClientProfileRepository clientProfileRepository;
    private final RiskResultRepository riskResultRepository;
    private final RiskThresholdConfig thresholdConfig;

    public BusinessInsightsService(ClientProfileRepository clientProfileRepository,
                                   RiskResultRepository riskResultRepository,
                                   RiskThresholdConfig thresholdConfig) {
        this.clientProfileRepository = clientProfileRepository;
        this.riskResultRepository = riskResultRepository;
        this.thresholdConfig = thresholdConfig;
    }

    // ───────────────────────────────────────────────────────────
    // CLIENT SEGMENTATION
    // ───────────────────────────────────────────────────────────

    public ClientSegment classifyClient(ClientProfile profile, double medianEwma) {
        if (profile.getTotalTxnCount() < NEW_CLIENT_TXN_THRESHOLD) {
            return ClientSegment.NEW;
        }

        long now = System.currentTimeMillis();
        if (now - profile.getLastUpdated() > DORMANCY_THRESHOLD_MS) {
            return ClientSegment.DORMANT;
        }

        if (profile.getEwmaAmount() >= medianEwma * 2.0 && profile.getTotalTxnCount() > 500) {
            return ClientSegment.HIGH_VALUE;
        }

        double growthSignal = computeGrowthSignal(profile);
        if (growthSignal > 0.15) return ClientSegment.GROWING;
        if (growthSignal < -0.15) return ClientSegment.DECLINING;

        return ClientSegment.STABLE;
    }

    public List<Map<String, Object>> getClientSegmentation() {
        List<ClientProfile> profiles = clientProfileRepository.scanAllProfiles();
        if (profiles.isEmpty()) return Collections.emptyList();

        double medianEwma = computeMedianEwma(profiles);

        List<Map<String, Object>> results = new ArrayList<>();
        for (ClientProfile p : profiles) {
            ClientSegment segment = classifyClient(p, medianEwma);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("clientId", p.getClientId());
            entry.put("segment", segment.name());
            entry.put("segmentLabel", segment.label());
            entry.put("totalTransactions", p.getTotalTxnCount());
            entry.put("ewmaAmount", round2(p.getEwmaAmount()));
            entry.put("distinctBeneficiaries", p.getDistinctBeneficiaryCount());
            entry.put("daysSinceLastActivity", daysSince(p.getLastUpdated()));
            entry.put("growthSignal", round2(computeGrowthSignal(p)));
            entry.put("primaryRail", getPrimaryRail(p));

            results.add(entry);
        }

        results.sort(Comparator.comparing(
                (Map<String, Object> m) -> ((String) m.get("segment"))));

        return results;
    }

    public Map<String, Object> getSegmentSummary() {
        List<ClientProfile> profiles = clientProfileRepository.scanAllProfiles();
        double medianEwma = computeMedianEwma(profiles);

        Map<ClientSegment, List<ClientProfile>> grouped = profiles.stream()
                .collect(Collectors.groupingBy(p -> classifyClient(p, medianEwma)));

        Map<String, Object> summary = new LinkedHashMap<>();
        for (ClientSegment seg : ClientSegment.values()) {
            List<ClientProfile> members = grouped.getOrDefault(seg, List.of());
            Map<String, Object> segInfo = new LinkedHashMap<>();
            segInfo.put("count", members.size());
            segInfo.put("totalTransactions", members.stream().mapToLong(ClientProfile::getTotalTxnCount).sum());
            segInfo.put("avgEwmaAmount", round2(members.stream()
                    .mapToDouble(ClientProfile::getEwmaAmount).average().orElse(0)));
            segInfo.put("clients", members.stream().map(ClientProfile::getClientId).collect(Collectors.toList()));
            summary.put(seg.name(), segInfo);
        }
        summary.put("totalClients", profiles.size());
        summary.put("medianEwmaAmount", round2(medianEwma));

        return summary;
    }

    // ───────────────────────────────────────────────────────────
    // RAIL INSIGHTS
    // ───────────────────────────────────────────────────────────

    public Map<String, Object> getSystemRailInsights() {
        List<ClientProfile> profiles = clientProfileRepository.scanAllProfiles();

        Map<String, Long> totalVolume = new LinkedHashMap<>();
        Map<String, Double> totalAmountByRail = new LinkedHashMap<>();
        Map<String, Set<String>> clientsByRail = new LinkedHashMap<>();

        for (String rail : thresholdConfig.getTransactionTypes()) {
            totalVolume.put(rail, 0L);
            totalAmountByRail.put(rail, 0.0);
            clientsByRail.put(rail, new HashSet<>());
        }

        for (ClientProfile p : profiles) {
            for (Map.Entry<String, Long> entry : p.getTxnTypeCounts().entrySet()) {
                String rail = entry.getKey();
                totalVolume.merge(rail, entry.getValue(), Long::sum);
                clientsByRail.computeIfAbsent(rail, k -> new HashSet<>()).add(p.getClientId());
            }
            for (Map.Entry<String, Double> entry : p.getAvgAmountByType().entrySet()) {
                long count = p.getTxnTypeCounts().getOrDefault(entry.getKey(), 0L);
                totalAmountByRail.merge(entry.getKey(), entry.getValue() * count, Double::sum);
            }
        }

        long grandTotal = totalVolume.values().stream().mapToLong(Long::longValue).sum();

        List<Map<String, Object>> rails = new ArrayList<>();
        for (String rail : thresholdConfig.getTransactionTypes()) {
            Map<String, Object> info = new LinkedHashMap<>();
            long vol = totalVolume.getOrDefault(rail, 0L);
            info.put("rail", rail);
            info.put("transactionCount", vol);
            info.put("volumeSharePct", grandTotal > 0 ? round2(vol * 100.0 / grandTotal) : 0);
            info.put("totalAmount", round2(totalAmountByRail.getOrDefault(rail, 0.0)));
            info.put("avgAmountPerTxn", vol > 0 ? round2(totalAmountByRail.getOrDefault(rail, 0.0) / vol) : 0);
            info.put("activeClients", clientsByRail.getOrDefault(rail, Set.of()).size());

            RailCharacteristics rc = RAIL_CHARACTERISTICS.get(rail);
            if (rc != null) {
                info.put("description", rc.description);
                info.put("realTime", rc.realTime);
            }
            rails.add(info);
        }

        rails.sort(Comparator.comparingLong(
                (Map<String, Object> m) -> ((Number) m.get("transactionCount")).longValue()).reversed());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("rails", rails);
        response.put("totalTransactions", grandTotal);
        response.put("totalClients", profiles.size());
        return response;
    }

    public Map<String, Object> getClientRailProfile(String clientId) {
        ClientProfile profile = clientProfileRepository.findByClientId(clientId);
        if (profile == null) {
            return Map.of("error", "Client not found", "clientId", clientId);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("clientId", clientId);

        long totalTxns = profile.getTotalTxnCount();
        List<Map<String, Object>> rails = new ArrayList<>();

        for (Map.Entry<String, Long> entry : profile.getTxnTypeCounts().entrySet()) {
            String rail = entry.getKey();
            long count = entry.getValue();

            Map<String, Object> railInfo = new LinkedHashMap<>();
            railInfo.put("rail", rail);
            railInfo.put("transactionCount", count);
            railInfo.put("usagePct", totalTxns > 0 ? round2(count * 100.0 / totalTxns) : 0);
            railInfo.put("avgAmount", round2(profile.getAvgAmountByType().getOrDefault(rail, 0.0)));
            railInfo.put("amountStdDev", round2(profile.getAmountStdDevForType(rail)));
            rails.add(railInfo);
        }

        rails.sort(Comparator.comparingLong(
                (Map<String, Object> m) -> ((Number) m.get("transactionCount")).longValue()).reversed());

        response.put("railUsage", rails);
        response.put("totalTransactions", totalTxns);
        response.put("migrationOpportunities", computeMigrationOpportunities(profile));

        return response;
    }

    public List<Map<String, Object>> getRailMigrationOpportunities() {
        List<ClientProfile> profiles = clientProfileRepository.scanAllProfiles();
        List<Map<String, Object>> allOpportunities = new ArrayList<>();

        for (ClientProfile p : profiles) {
            List<Map<String, Object>> ops = computeMigrationOpportunities(p);
            for (Map<String, Object> op : ops) {
                op.put("clientId", p.getClientId());
                allOpportunities.add(op);
            }
        }

        allOpportunities.sort(Comparator.comparingDouble(
                (Map<String, Object> m) -> ((Number) m.get("impactScore")).doubleValue()).reversed());

        return allOpportunities;
    }

    // ───────────────────────────────────────────────────────────
    // CAMPAIGN INTELLIGENCE
    // ───────────────────────────────────────────────────────────

    public List<Map<String, Object>> getCampaignRecommendations() {
        List<ClientProfile> profiles = clientProfileRepository.scanAllProfiles();
        double medianEwma = computeMedianEwma(profiles);

        List<Map<String, Object>> campaigns = new ArrayList<>();

        Map<ClientSegment, List<ClientProfile>> grouped = profiles.stream()
                .collect(Collectors.groupingBy(p -> classifyClient(p, medianEwma)));

        // Campaign 1: UPI adoption for clients barely using it
        List<String> lowUpiClients = profiles.stream()
                .filter(p -> p.getTotalTxnCount() > NEW_CLIENT_TXN_THRESHOLD)
                .filter(p -> p.getTypeFrequency("UPI") < 0.10)
                .map(ClientProfile::getClientId)
                .collect(Collectors.toList());
        if (!lowUpiClients.isEmpty()) {
            campaigns.add(buildCampaign("UPI_ADOPTION",
                    "UPI Adoption Drive",
                    "Clients with <10% UPI usage — target with cashback incentives to shift low-value NEFT/IMPS transactions to UPI",
                    lowUpiClients, "HIGH"));
        }

        // Campaign 2: Re-engagement for dormant clients
        List<String> dormantClients = grouped.getOrDefault(ClientSegment.DORMANT, List.of())
                .stream().map(ClientProfile::getClientId).collect(Collectors.toList());
        if (!dormantClients.isEmpty()) {
            campaigns.add(buildCampaign("RE_ENGAGEMENT",
                    "Dormant Client Re-engagement",
                    "Clients inactive for 7+ days — re-activate with targeted offers on their preferred rail",
                    dormantClients, "MEDIUM"));
        }

        // Campaign 3: High-value client retention
        List<String> highValueClients = grouped.getOrDefault(ClientSegment.HIGH_VALUE, List.of())
                .stream().map(ClientProfile::getClientId).collect(Collectors.toList());
        if (!highValueClients.isEmpty()) {
            campaigns.add(buildCampaign("HV_RETENTION",
                    "High-Value Client Retention",
                    "Top-tier clients — offer premium RTGS benefits, dedicated support, or bulk transaction discounts",
                    highValueClients, "HIGH"));
        }

        // Campaign 4: Growing client upsell
        List<String> growingClients = grouped.getOrDefault(ClientSegment.GROWING, List.of())
                .stream().map(ClientProfile::getClientId).collect(Collectors.toList());
        if (!growingClients.isEmpty()) {
            campaigns.add(buildCampaign("GROWTH_UPSELL",
                    "Growing Client Upsell",
                    "Clients with increasing transaction velocity — introduce higher-value rails (RTGS) or premium services",
                    growingClients, "MEDIUM"));
        }

        // Campaign 5: RTGS migration for heavy NEFT users with high amounts
        List<String> neftToRtgs = profiles.stream()
                .filter(p -> p.getTotalTxnCount() > NEW_CLIENT_TXN_THRESHOLD)
                .filter(p -> {
                    double neftAvg = p.getAvgAmountByType().getOrDefault("NEFT", 0.0);
                    double neftFreq = p.getTypeFrequency("NEFT");
                    return neftAvg > 200_000 && neftFreq > 0.3;
                })
                .map(ClientProfile::getClientId)
                .collect(Collectors.toList());
        if (!neftToRtgs.isEmpty()) {
            campaigns.add(buildCampaign("NEFT_TO_RTGS",
                    "NEFT-to-RTGS Migration",
                    "Clients sending high-value NEFT (avg >₹2L) — migrate to RTGS for real-time settlement and reduced counterparty risk",
                    neftToRtgs, "HIGH"));
        }

        // Campaign 6: Beneficiary diversification
        List<String> concentratedClients = profiles.stream()
                .filter(p -> p.getTotalTxnCount() > 200)
                .filter(p -> p.getDistinctBeneficiaryCount() > 0 && p.getDistinctBeneficiaryCount() < 5)
                .map(ClientProfile::getClientId)
                .collect(Collectors.toList());
        if (!concentratedClients.isEmpty()) {
            campaigns.add(buildCampaign("BENE_DIVERSIFICATION",
                    "Beneficiary Network Expansion",
                    "Active clients with very few beneficiaries — potential for supplier/vendor onboarding campaigns",
                    concentratedClients, "LOW"));
        }

        return campaigns;
    }

    // ───────────────────────────────────────────────────────────
    // VOLUME & REVENUE INSIGHTS
    // ───────────────────────────────────────────────────────────

    public Map<String, Object> getVolumeInsights() {
        List<ClientProfile> profiles = clientProfileRepository.scanAllProfiles();

        Map<String, Object> response = new LinkedHashMap<>();

        // Peak hour analysis from seasonal profiles
        Map<String, Double> systemHourlyTps = new LinkedHashMap<>();
        Map<String, Double> systemHourlyAmt = new LinkedHashMap<>();
        for (int h = 0; h < 24; h++) {
            String slot = String.format("%02d", h);
            double totalTps = 0;
            double totalAmt = 0;
            for (ClientProfile p : profiles) {
                totalTps += p.getSeasonalHourlyTps().getOrDefault(slot, 0.0);
                totalAmt += p.getSeasonalHourlyAmt().getOrDefault(slot, 0.0);
            }
            systemHourlyTps.put(slot, round2(totalTps));
            systemHourlyAmt.put(slot, round2(totalAmt));
        }

        String peakHour = systemHourlyTps.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("--");
        String peakAmtHour = systemHourlyAmt.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("--");

        response.put("hourlyTpsDistribution", systemHourlyTps);
        response.put("hourlyAmountDistribution", systemHourlyAmt);
        response.put("peakTpsHour", peakHour);
        response.put("peakAmountHour", peakAmtHour);

        // Day-of-week analysis
        String[] dayNames = {"", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        Map<String, Double> systemDailyAmt = new LinkedHashMap<>();
        Map<String, Double> systemDailyTps = new LinkedHashMap<>();
        for (int d = 1; d <= 7; d++) {
            String slot = String.valueOf(d);
            double totalAmt = 0;
            double totalTps = 0;
            for (ClientProfile p : profiles) {
                totalAmt += p.getSeasonalDailyAmt().getOrDefault(slot, 0.0);
                totalTps += p.getSeasonalDailyTps().getOrDefault(slot, 0.0);
            }
            systemDailyAmt.put(dayNames[d], round2(totalAmt));
            systemDailyTps.put(dayNames[d], round2(totalTps));
        }

        response.put("dailyAmountDistribution", systemDailyAmt);
        response.put("dailyTpsDistribution", systemDailyTps);

        String peakDay = systemDailyAmt.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("--");
        response.put("peakAmountDay", peakDay);

        // System totals
        long totalTxns = profiles.stream().mapToLong(ClientProfile::getTotalTxnCount).sum();
        double totalDailyVolume = profiles.stream().mapToDouble(ClientProfile::getEwmaDailyAmount).sum();
        response.put("totalTransactions", totalTxns);
        response.put("systemEwmaDailyVolume", round2(totalDailyVolume));
        response.put("totalClients", profiles.size());
        response.put("avgTxnPerClient", profiles.isEmpty() ? 0 : totalTxns / profiles.size());

        return response;
    }

    public Map<String, Object> getClientInsightProfile(String clientId) {
        ClientProfile profile = clientProfileRepository.findByClientId(clientId);
        if (profile == null) {
            return Map.of("error", "Client not found", "clientId", clientId);
        }

        List<ClientProfile> allProfiles = clientProfileRepository.scanAllProfiles();
        double medianEwma = computeMedianEwma(allProfiles);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("clientId", clientId);

        // Segmentation
        ClientSegment segment = classifyClient(profile, medianEwma);
        response.put("segment", segment.name());
        response.put("segmentLabel", segment.label());

        // Key metrics
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("totalTransactions", profile.getTotalTxnCount());
        metrics.put("ewmaAmount", round2(profile.getEwmaAmount()));
        metrics.put("amountStdDev", round2(profile.getAmountStdDev()));
        metrics.put("ewmaHourlyTps", round2(profile.getEwmaHourlyTps()));
        metrics.put("ewmaDailyAmount", round2(profile.getEwmaDailyAmount()));
        metrics.put("distinctBeneficiaries", profile.getDistinctBeneficiaryCount());
        metrics.put("daysSinceLastActivity", daysSince(profile.getLastUpdated()));
        metrics.put("growthSignal", round2(computeGrowthSignal(profile)));
        response.put("metrics", metrics);

        // Rail breakdown
        response.put("railProfile", getClientRailProfile(clientId));

        // Risk summary
        PagedResponse<EvaluationResult> evals = riskResultRepository.findByClientId(clientId, 100, null);
        List<EvaluationResult> evalList = evals != null ? evals.data() : List.of();
        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("totalEvaluations", evalList.size());
        risk.put("alertCount", evalList.stream().filter(e -> "ALERT".equals(e.getAction())).count());
        risk.put("blockCount", evalList.stream().filter(e -> "BLOCK".equals(e.getAction())).count());
        risk.put("avgCompositeScore", round2(evalList.stream()
                .mapToDouble(EvaluationResult::getCompositeScore).average().orElse(0)));
        risk.put("riskTrend", evalList.size() >= 10 ? computeRiskTrend(evalList) : "INSUFFICIENT_DATA");
        response.put("riskSummary", risk);

        // Seasonal fingerprint (peak hours, peak days)
        Map<String, Object> seasonal = new LinkedHashMap<>();
        String peakHour = profile.getSeasonalHourlyTps().entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("--");
        seasonal.put("peakHour", peakHour);

        String[] dayNames = {"", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        String peakDaySlot = profile.getSeasonalDailyAmt().entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("1");
        try {
            seasonal.put("peakDay", dayNames[Integer.parseInt(peakDaySlot)]);
        } catch (Exception e) {
            seasonal.put("peakDay", "Unknown");
        }
        response.put("seasonalFingerprint", seasonal);

        // Applicable campaigns
        response.put("applicableCampaigns", getApplicableCampaigns(profile, segment));

        return response;
    }

    // ───────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ───────────────────────────────────────────────────────────

    private double computeGrowthSignal(ClientProfile profile) {
        if (profile.getCompletedDaysCount() < 7) return 0.0;

        // Compare weekday vs weekend daily amounts as a proxy for trend.
        // More robustly: compare recent daily EWMA to overall EWMA.
        // growthSignal = (ewmaDailyAmount - ewmaAmount * ewmaHourlyTps * 24) / max(ewmaAmount * ewmaHourlyTps * 24, 1)
        // Simplified: use the ratio of current daily EWMA to what we'd expect from hourly stats
        double expectedDaily = profile.getEwmaHourlyTps() * profile.getEwmaAmount();
        if (expectedDaily < 1.0) return 0.0;

        double actual = profile.getEwmaDailyAmount();
        return (actual - expectedDaily) / expectedDaily;
    }

    private List<Map<String, Object>> computeMigrationOpportunities(ClientProfile profile) {
        List<Map<String, Object>> opportunities = new ArrayList<>();

        // Opportunity 1: NEFT with high avg amount → RTGS
        double neftAvg = profile.getAvgAmountByType().getOrDefault("NEFT", 0.0);
        long neftCount = profile.getTxnTypeCounts().getOrDefault("NEFT", 0L);
        if (neftAvg > 200_000 && neftCount > 10) {
            Map<String, Object> op = new LinkedHashMap<>();
            op.put("fromRail", "NEFT");
            op.put("toRail", "RTGS");
            op.put("reason", "Average NEFT amount ₹" + Math.round(neftAvg) + " exceeds RTGS threshold — real-time settlement would reduce counterparty risk");
            op.put("affectedTransactions", neftCount);
            op.put("avgAmount", round2(neftAvg));
            op.put("impactScore", round2(neftAvg * neftCount / 1_000_000.0));
            opportunities.add(op);
        }

        // Opportunity 2: IMPS with very small amounts → UPI
        double impsAvg = profile.getAvgAmountByType().getOrDefault("IMPS", 0.0);
        long impsCount = profile.getTxnTypeCounts().getOrDefault("IMPS", 0L);
        if (impsAvg < 5_000 && impsCount > 20) {
            Map<String, Object> op = new LinkedHashMap<>();
            op.put("fromRail", "IMPS");
            op.put("toRail", "UPI");
            op.put("reason", "Average IMPS amount ₹" + Math.round(impsAvg) + " is ideal for zero-cost UPI transactions");
            op.put("affectedTransactions", impsCount);
            op.put("avgAmount", round2(impsAvg));
            op.put("impactScore", round2(impsCount * 2.5));
            opportunities.add(op);
        }

        // Opportunity 3: NEFT with small amounts → UPI
        if (neftAvg < 10_000 && neftCount > 20) {
            Map<String, Object> op = new LinkedHashMap<>();
            op.put("fromRail", "NEFT");
            op.put("toRail", "UPI");
            op.put("reason", "Low-value NEFT (avg ₹" + Math.round(neftAvg) + ") could move to instant, zero-cost UPI");
            op.put("affectedTransactions", neftCount);
            op.put("avgAmount", round2(neftAvg));
            op.put("impactScore", round2(neftCount * 1.5));
            opportunities.add(op);
        }

        // Opportunity 4: RTGS with small amounts → NEFT or IMPS
        double rtgsAvg = profile.getAvgAmountByType().getOrDefault("RTGS", 0.0);
        long rtgsCount = profile.getTxnTypeCounts().getOrDefault("RTGS", 0L);
        if (rtgsAvg < 200_000 && rtgsCount > 5) {
            Map<String, Object> op = new LinkedHashMap<>();
            op.put("fromRail", "RTGS");
            op.put("toRail", "NEFT");
            op.put("reason", "RTGS avg ₹" + Math.round(rtgsAvg) + " is below typical RTGS threshold — NEFT is more cost-effective");
            op.put("affectedTransactions", rtgsCount);
            op.put("avgAmount", round2(rtgsAvg));
            op.put("impactScore", round2(rtgsCount * 3.0));
            opportunities.add(op);
        }

        opportunities.sort(Comparator.comparingDouble(
                (Map<String, Object> m) -> ((Number) m.get("impactScore")).doubleValue()).reversed());

        return opportunities;
    }

    private Map<String, Object> buildCampaign(String id, String name, String description,
                                               List<String> targetClients, String priority) {
        Map<String, Object> campaign = new LinkedHashMap<>();
        campaign.put("campaignId", id);
        campaign.put("name", name);
        campaign.put("description", description);
        campaign.put("priority", priority);
        campaign.put("targetClientCount", targetClients.size());
        campaign.put("targetClients", targetClients);
        return campaign;
    }

    private List<String> getApplicableCampaigns(ClientProfile profile, ClientSegment segment) {
        List<String> campaigns = new ArrayList<>();

        if (profile.getTypeFrequency("UPI") < 0.10 && profile.getTotalTxnCount() > NEW_CLIENT_TXN_THRESHOLD) {
            campaigns.add("UPI_ADOPTION");
        }
        if (segment == ClientSegment.DORMANT) {
            campaigns.add("RE_ENGAGEMENT");
        }
        if (segment == ClientSegment.HIGH_VALUE) {
            campaigns.add("HV_RETENTION");
        }
        if (segment == ClientSegment.GROWING) {
            campaigns.add("GROWTH_UPSELL");
        }
        double neftAvg = profile.getAvgAmountByType().getOrDefault("NEFT", 0.0);
        if (neftAvg > 200_000 && profile.getTypeFrequency("NEFT") > 0.3) {
            campaigns.add("NEFT_TO_RTGS");
        }
        if (profile.getTotalTxnCount() > 200 && profile.getDistinctBeneficiaryCount() < 5) {
            campaigns.add("BENE_DIVERSIFICATION");
        }

        return campaigns;
    }

    private String computeRiskTrend(List<EvaluationResult> evals) {
        int half = evals.size() / 2;
        double recentAvg = evals.subList(0, half).stream()
                .mapToDouble(EvaluationResult::getCompositeScore).average().orElse(0);
        double olderAvg = evals.subList(half, evals.size()).stream()
                .mapToDouble(EvaluationResult::getCompositeScore).average().orElse(0);

        double delta = recentAvg - olderAvg;
        if (delta > 5) return "INCREASING";
        if (delta < -5) return "DECREASING";
        return "STABLE";
    }

    private String getPrimaryRail(ClientProfile profile) {
        return profile.getTxnTypeCounts().entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("NONE");
    }

    private double computeMedianEwma(List<ClientProfile> profiles) {
        List<Double> amounts = profiles.stream()
                .map(ClientProfile::getEwmaAmount)
                .filter(a -> a > 0)
                .sorted()
                .collect(Collectors.toList());
        if (amounts.isEmpty()) return 0.0;
        return amounts.get(amounts.size() / 2);
    }

    private long daysSince(long timestampMs) {
        if (timestampMs <= 0) return -1;
        return (System.currentTimeMillis() - timestampMs) / (24 * 60 * 60 * 1000);
    }

    private double round2(double val) {
        return Math.round(val * 100.0) / 100.0;
    }

    record RailCharacteristics(double minAmount, double maxAmount, boolean realTime, String description) {}
}
