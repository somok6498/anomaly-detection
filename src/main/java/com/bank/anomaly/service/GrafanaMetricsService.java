package com.bank.anomaly.service;

import com.bank.anomaly.model.BucketEntry;
import com.bank.anomaly.repository.ClientProfileRepository;
import com.bank.anomaly.repository.MetricsBucketRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class GrafanaMetricsService {

    private static final List<String> ACTIONS = List.of("PASS", "ALERT", "BLOCK");
    private static final List<String> TXN_TYPES = List.of("NEFT", "RTGS", "IMPS", "UPI", "IFT");
    private static final List<String> RULE_TYPES = List.of(
            "AMOUNT_ANOMALY", "TPS_SPIKE", "HOURLY_AMOUNT_ANOMALY",
            "TRANSACTION_TYPE_ANOMALY", "AMOUNT_PER_TYPE_ANOMALY",
            "DAILY_CUMULATIVE_AMOUNT", "BENEFICIARY_RAPID_REPEAT",
            "BENEFICIARY_CONCENTRATION", "BENEFICIARY_AMOUNT_REPETITION",
            "BENEFICIARY_CROSS_CHANNEL", "BENEFICIARY_NEW_VELOCITY",
            "SEASONAL_HOURLY_TPS", "SEASONAL_HOURLY_AMOUNT",
            "SEASONAL_DAILY_AMOUNT", "ISOLATION_FOREST", "SILENCE_DETECTION"
    );

    private final MetricsBucketRepository bucketRepo;
    private final ClientProfileRepository profileRepo;
    private final BusinessInsightsService insightsService;
    private final SilenceDetectionService silenceService;

    public GrafanaMetricsService(MetricsBucketRepository bucketRepo,
                                  ClientProfileRepository profileRepo,
                                  BusinessInsightsService insightsService,
                                  SilenceDetectionService silenceService) {
        this.bucketRepo = bucketRepo;
        this.profileRepo = profileRepo;
        this.insightsService = insightsService;
        this.silenceService = silenceService;
    }

    // ─── TIME-SERIES ──────────────────────────────────────────

    public List<Map<String, Object>> getTimeSeriesEvaluations(long from, long to, String clientId) {
        String scope = resolveScope(clientId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (String action : ACTIONS) {
            for (BucketEntry e : bucketRepo.queryRange(scope, "eval_count_" + action, from, to)) {
                result.add(tsPoint(e.getTimestamp(), e.getCount(), action));
            }
        }
        result.sort(Comparator.comparingLong(a -> (long) a.get("time")));
        return result;
    }

    public List<Map<String, Object>> getTimeSeriesCompositeScore(long from, long to,
                                                                   String clientId, String calc) {
        String scope = resolveScope(clientId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (String action : ACTIONS) {
            for (BucketEntry e : bucketRepo.queryRange(scope, "eval_score_" + action, from, to)) {
                double value = "max".equals(calc) ? e.getMax()
                        : (e.getCount() > 0 ? e.getSum() / e.getCount() : 0);
                result.add(tsPoint(e.getTimestamp(), value, action));
            }
        }
        result.sort(Comparator.comparingLong(a -> (long) a.get("time")));
        return result;
    }

    public List<Map<String, Object>> getTimeSeriesRules(long from, long to,
                                                         String clientId, String ruleType) {
        String scope = resolveScope(clientId);
        List<String> metrics = ruleType != null ? List.of("rule_" + ruleType)
                : RULE_TYPES.stream().map(r -> "rule_" + r).collect(Collectors.toList());

        List<Map<String, Object>> result = new ArrayList<>();
        for (String metric : metrics) {
            String label = metric.substring(5); // strip "rule_"
            for (BucketEntry e : bucketRepo.queryRange(scope, metric, from, to)) {
                result.add(tsPoint(e.getTimestamp(), e.getCount(), label));
            }
        }
        result.sort(Comparator.comparingLong(a -> (long) a.get("time")));
        return result;
    }

    public List<Map<String, Object>> getTimeSeriesNotifications(long from, long to) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String ch : List.of("whatsapp", "sms")) {
            for (String st : List.of("success", "error")) {
                String metric = "notif_" + ch + "_" + st;
                for (BucketEntry e : bucketRepo.queryRange("SYSTEM", metric, from, to)) {
                    result.add(tsPoint(e.getTimestamp(), e.getCount(), ch + "_" + st));
                }
            }
        }
        result.sort(Comparator.comparingLong(a -> (long) a.get("time")));
        return result;
    }

    public List<Map<String, Object>> getTimeSeriesTxnAmount(long from, long to,
                                                              String clientId, String txnType, String calc) {
        String scope = resolveScope(clientId);
        List<String> types = txnType != null ? List.of(txnType) : TXN_TYPES;
        List<Map<String, Object>> result = new ArrayList<>();
        for (String t : types) {
            for (BucketEntry e : bucketRepo.queryRange(scope, "txn_amount_" + t, from, to)) {
                double value;
                if ("max".equals(calc)) value = e.getMax();
                else if ("sum".equals(calc)) value = e.getSum();
                else value = e.getCount() > 0 ? e.getSum() / e.getCount() : 0;
                result.add(tsPoint(e.getTimestamp(), value, t));
            }
        }
        result.sort(Comparator.comparingLong(a -> (long) a.get("time")));
        return result;
    }

    public List<Map<String, Object>> getTimeSeriesTxnTypeCount(long from, long to,
                                                                 String clientId, String txnType) {
        String scope = resolveScope(clientId);
        List<String> types = txnType != null ? List.of(txnType) : TXN_TYPES;
        List<Map<String, Object>> result = new ArrayList<>();
        for (String t : types) {
            for (BucketEntry e : bucketRepo.queryRange(scope, "txn_type_" + t, from, to)) {
                result.add(tsPoint(e.getTimestamp(), e.getCount(), t));
            }
        }
        result.sort(Comparator.comparingLong(a -> (long) a.get("time")));
        return result;
    }

    public List<Map<String, Object>> getTimeSeriesClientVolume(long from, long to, String clientId) {
        String scope = resolveScope(clientId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (String t : TXN_TYPES) {
            for (BucketEntry e : bucketRepo.queryRange(scope, "txn_type_" + t, from, to)) {
                result.add(tsPoint(e.getTimestamp(), e.getCount(), t));
            }
        }
        result.sort(Comparator.comparingLong(a -> (long) a.get("time")));
        return result;
    }

    public List<Map<String, Object>> getTimeSeriesSilence(long from, long to) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String metric : List.of("silence_detected", "silence_resolved")) {
            for (BucketEntry e : bucketRepo.queryRange("SYSTEM", metric, from, to)) {
                result.add(tsPoint(e.getTimestamp(), e.getCount(), metric));
            }
        }
        result.sort(Comparator.comparingLong(a -> (long) a.get("time")));
        return result;
    }

    public List<Map<String, Object>> getHourlyTpsDistribution(String clientId) {
        var volume = isValidClientId(clientId)
                ? insightsService.getClientInsightProfile(clientId.toUpperCase())
                : insightsService.getVolumeInsights();
        @SuppressWarnings("unchecked")
        Map<String, Object> hourlyMap = (Map<String, Object>) volume.getOrDefault(
                "hourlyTpsDistribution", Map.of());
        List<Map<String, Object>> result = new ArrayList<>();
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        for (int h = 0; h < 24; h++) {
            String slot = String.format("%02d", h);
            Object val = hourlyMap.get(slot);
            double tps = val instanceof Number ? ((Number) val).doubleValue() : 0;
            long epochMs = today.atTime(h, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
            result.add(Map.of("time", epochMs, "value", tps));
        }
        return result;
    }

    public List<Map<String, Object>> getDailyAmountDistribution(String clientId) {
        var volume = isValidClientId(clientId)
                ? insightsService.getClientInsightProfile(clientId.toUpperCase())
                : insightsService.getVolumeInsights();
        @SuppressWarnings("unchecked")
        Map<String, Object> dailyMap = (Map<String, Object>) volume.getOrDefault(
                "dailyAmountDistribution", Map.of());
        List<Map<String, Object>> result = new ArrayList<>();
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        java.time.LocalDate monday = today.with(java.time.DayOfWeek.MONDAY);
        int i = 0;
        for (String day : List.of("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")) {
            Object val = dailyMap.get(day);
            double amt = val instanceof Number ? ((Number) val).doubleValue() : 0;
            long epochMs = monday.plusDays(i).atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
            result.add(Map.of("time", epochMs, "value", amt));
            i++;
        }
        return result;
    }

    // ─── STATS ────────────────────────────────────────────────

    public List<Map<String, Object>> getStatsEvaluations(long from, long to, String clientId) {
        String scope = resolveScope(clientId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (String action : ACTIONS) {
            long total = bucketRepo.queryRange(scope, "eval_count_" + action, from, to)
                    .stream().mapToLong(BucketEntry::getCount).sum();
            result.add(Map.of("label", action, "value", total));
        }
        return result;
    }

    public List<Map<String, Object>> getStatsRules(long from, long to, String clientId) {
        String scope = resolveScope(clientId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (String rule : RULE_TYPES) {
            long total = bucketRepo.queryRange(scope, "rule_" + rule, from, to)
                    .stream().mapToLong(BucketEntry::getCount).sum();
            if (total > 0) {
                result.add(Map.of("label", rule, "value", total));
            }
        }
        result.sort((a, b) -> Long.compare((long) b.get("value"), (long) a.get("value")));
        return result;
    }

    public List<Map<String, Object>> getStatsNotifications(long from, long to) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String ch : List.of("whatsapp", "sms")) {
            for (String st : List.of("success", "error")) {
                long total = bucketRepo.queryRange("SYSTEM", "notif_" + ch + "_" + st, from, to)
                        .stream().mapToLong(BucketEntry::getCount).sum();
                if (total > 0) {
                    result.add(Map.of("label", ch + "_" + st, "value", total));
                }
            }
        }
        return result;
    }

    public List<Map<String, Object>> getStatsSilence() {
        int count = silenceService.getAlertedClients().size();
        return List.of(Map.of("label", "Silent Clients", "value", count));
    }

    public List<Map<String, Object>> getStatsAlertsBlocks1h() {
        long now = System.currentTimeMillis();
        long oneHourAgo = now - 3600_000L;
        long alerts = bucketRepo.queryRange("SYSTEM", "eval_count_ALERT", oneHourAgo, now)
                .stream().mapToLong(BucketEntry::getCount).sum();
        long blocks = bucketRepo.queryRange("SYSTEM", "eval_count_BLOCK", oneHourAgo, now)
                .stream().mapToLong(BucketEntry::getCount).sum();
        return List.of(Map.of("label", "Alerts + Blocks (1h)", "value", alerts + blocks));
    }

    public List<Map<String, Object>> getStatsFlaggedClients(long from, long to) {
        var profiles = profileRepo.scanAllProfiles();
        int count = 0;
        for (var profile : profiles) {
            String cid = profile.getClientId();
            long total = RULE_TYPES.stream()
                    .mapToLong(r -> bucketRepo.queryRange(cid, "rule_" + r, from, to)
                            .stream().mapToLong(BucketEntry::getCount).sum())
                    .sum();
            if (total > 0) count++;
        }
        return List.of(Map.of("label", "Flagged Clients", "value", count));
    }

    public List<Map<String, Object>> getStatsTopRule(long from, long to) {
        String topRule = "";
        long topCount = 0;
        for (String rule : RULE_TYPES) {
            long total = bucketRepo.queryRange("SYSTEM", "rule_" + rule, from, to)
                    .stream().mapToLong(BucketEntry::getCount).sum();
            if (total > topCount) {
                topCount = total;
                topRule = rule;
            }
        }
        return List.of(Map.of("label", topRule, "value", topCount));
    }

    public List<Map<String, Object>> getStatsVolume() {
        var volume = insightsService.getVolumeInsights();
        return List.of(
                Map.of("label", "Peak TPS Hour", "value", volume.getOrDefault("peakTpsHour", "--")),
                Map.of("label", "Peak Amount Day", "value", volume.getOrDefault("peakAmountDay", "--")),
                Map.of("label", "Daily Volume", "value", volume.getOrDefault("systemEwmaDailyVolume", 0))
        );
    }

    // ─── TABLES ───────────────────────────────────────────────

    public List<Map<String, Object>> getTableClientList() {
        return profileRepo.scanAllProfiles().stream()
                .map(p -> Map.<String, Object>of("clientId", p.getClientId()))
                .sorted(Comparator.comparing(m -> (String) m.get("clientId")))
                .collect(Collectors.toList());
    }

    public List<Map<String, Object>> getTableRuleBreakdown(long from, long to) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String rule : RULE_TYPES) {
            long total = bucketRepo.queryRange("SYSTEM", "rule_" + rule, from, to)
                    .stream().mapToLong(BucketEntry::getCount).sum();
            if (total > 0) {
                result.add(Map.of("rule", rule, "triggers", total));
            }
        }
        result.sort((a, b) -> Long.compare((long) b.get("triggers"), (long) a.get("triggers")));
        return result;
    }

    public List<Map<String, Object>> getTableFlaggedClients(long from, long to, String ruleCategory) {
        var profiles = profileRepo.scanAllProfiles();
        List<String> filteredRules;
        if (ruleCategory != null && !ruleCategory.isEmpty()) {
            filteredRules = RULE_TYPES.stream()
                    .filter(r -> r.startsWith(ruleCategory) || r.contains(ruleCategory))
                    .collect(Collectors.toList());
        } else {
            filteredRules = RULE_TYPES;
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (var profile : profiles) {
            String cid = profile.getClientId();
            long total = filteredRules.stream()
                    .mapToLong(r -> bucketRepo.queryRange(cid, "rule_" + r, from, to)
                            .stream().mapToLong(BucketEntry::getCount).sum())
                    .sum();
            if (total > 0) {
                result.add(Map.of("clientId", cid, "triggers", total));
            }
        }
        result.sort((a, b) -> Long.compare((long) b.get("triggers"), (long) a.get("triggers")));
        return result;
    }

    public List<Map<String, Object>> getTableSilentClients() {
        var silentClients = silenceService.getAlertedClients();
        List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : silentClients.entrySet()) {
            result.add(Map.of(
                    "clientId", entry.getKey(),
                    "lastTxnEpoch", entry.getValue()
            ));
        }
        return result;
    }

    public List<Map<String, Object>> getTableSegments() {
        return insightsService.getClientSegmentation();
    }

    public List<Map<String, Object>> getTableRailUsage(String clientId) {
        if (isValidClientId(clientId)) {
            var profile = insightsService.getClientRailProfile(clientId.toUpperCase());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rails = (List<Map<String, Object>>) profile.getOrDefault("railUsage", List.of());
            return rails;
        }
        var system = insightsService.getSystemRailInsights();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rails = (List<Map<String, Object>>) system.getOrDefault("rails", List.of());
        return rails;
    }

    public List<Map<String, Object>> getTableCampaigns() {
        return insightsService.getCampaignRecommendations();
    }

    public List<Map<String, Object>> getTableMigrationOpportunities() {
        return insightsService.getRailMigrationOpportunities();
    }

    // ─── PIE ──────────────────────────────────────────────────

    public List<Map<String, Object>> getPieEvaluationsByAction(long from, long to, String clientId) {
        return getStatsEvaluations(from, to, clientId);
    }

    public List<Map<String, Object>> getPieSegmentDistribution() {
        var summary = insightsService.getSegmentSummary();
        List<Map<String, Object>> result = new ArrayList<>();
        for (String seg : List.of("HIGH_VALUE", "GROWING", "STABLE", "DECLINING", "DORMANT", "NEW")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) summary.getOrDefault(seg, Map.of());
            int count = data.containsKey("count") ? ((Number) data.get("count")).intValue() : 0;
            if (count > 0) {
                result.add(Map.of("label", seg, "value", count));
            }
        }
        return result;
    }

    public List<Map<String, Object>> getPieRailDistribution() {
        var system = insightsService.getSystemRailInsights();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rails = (List<Map<String, Object>>) system.getOrDefault("rails", List.of());
        List<Map<String, Object>> result = new ArrayList<>();
        for (var rail : rails) {
            result.add(Map.of(
                    "label", rail.getOrDefault("rail", ""),
                    "value", rail.getOrDefault("transactionCount", 0)
            ));
        }
        return result;
    }

    public List<Map<String, Object>> getPieTxnTypeDistribution(long from, long to, String clientId) {
        String scope = resolveScope(clientId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (String t : TXN_TYPES) {
            long total = bucketRepo.queryRange(scope, "txn_type_" + t, from, to)
                    .stream().mapToLong(BucketEntry::getCount).sum();
            if (total > 0) {
                result.add(Map.of("label", t, "value", total));
            }
        }
        return result;
    }

    // ─── HELPERS ──────────────────────────────────────────────

    private boolean isValidClientId(String clientId) {
        return clientId != null && !clientId.isEmpty()
                && !clientId.contains("$") && !clientId.equalsIgnoreCase("All");
    }

    private String resolveScope(String clientId) {
        return isValidClientId(clientId) ? clientId.toUpperCase() : "SYSTEM";
    }

    private Map<String, Object> tsPoint(long time, Number value, String label) {
        return Map.of("time", time, "value", value, "label", label);
    }
}
