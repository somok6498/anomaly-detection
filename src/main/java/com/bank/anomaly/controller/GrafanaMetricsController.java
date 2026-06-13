package com.bank.anomaly.controller;

import com.bank.anomaly.service.GrafanaMetricsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/grafana")
@Tag(name = "Grafana Metrics", description = "JSON endpoints for Grafana Infinity plugin")
public class GrafanaMetricsController {

    private final GrafanaMetricsService service;

    public GrafanaMetricsController(GrafanaMetricsService service) {
        this.service = service;
    }

    // ─── TIME-SERIES ──────────────────────────────────────────

    @GetMapping("/timeseries/evaluations")
    @Operation(summary = "Evaluation counts over time by action")
    public List<Map<String, Object>> timeSeriesEvaluations(
            @RequestParam long from, @RequestParam long to,
            @RequestParam(required = false) String clientId) {
        return service.getTimeSeriesEvaluations(from, to, clientId);
    }

    @GetMapping("/timeseries/composite-score")
    @Operation(summary = "Composite score over time (avg or max)")
    public List<Map<String, Object>> timeSeriesCompositeScore(
            @RequestParam long from, @RequestParam long to,
            @RequestParam(required = false) String clientId,
            @RequestParam(defaultValue = "avg") String calc) {
        return service.getTimeSeriesCompositeScore(from, to, clientId, calc);
    }

    @GetMapping("/timeseries/rules")
    @Operation(summary = "Rule trigger counts over time by type")
    public List<Map<String, Object>> timeSeriesRules(
            @RequestParam long from, @RequestParam long to,
            @RequestParam(required = false) String clientId,
            @RequestParam(required = false) String ruleType) {
        return service.getTimeSeriesRules(from, to, clientId, ruleType);
    }

    @GetMapping("/timeseries/notifications")
    @Operation(summary = "Notification counts over time")
    public List<Map<String, Object>> timeSeriesNotifications(
            @RequestParam long from, @RequestParam long to) {
        return service.getTimeSeriesNotifications(from, to);
    }

    @GetMapping("/timeseries/txn-amount")
    @Operation(summary = "Transaction amount stats over time by type")
    public List<Map<String, Object>> timeSeriesTxnAmount(
            @RequestParam long from, @RequestParam long to,
            @RequestParam(required = false) String clientId,
            @RequestParam(required = false) String txnType,
            @RequestParam(defaultValue = "avg") String calc) {
        return service.getTimeSeriesTxnAmount(from, to, clientId, txnType, calc);
    }

    @GetMapping("/timeseries/txn-type-count")
    @Operation(summary = "Transaction type counts over time")
    public List<Map<String, Object>> timeSeriesTxnTypeCount(
            @RequestParam long from, @RequestParam long to,
            @RequestParam(required = false) String clientId,
            @RequestParam(required = false) String txnType) {
        return service.getTimeSeriesTxnTypeCount(from, to, clientId, txnType);
    }

    @GetMapping("/timeseries/client-volume")
    @Operation(summary = "Transaction volume over time for a client")
    public List<Map<String, Object>> clientVolume(
            @RequestParam long from, @RequestParam long to,
            @RequestParam(required = false) String clientId) {
        return service.getTimeSeriesClientVolume(from, to, clientId);
    }

    @GetMapping("/timeseries/silence")
    @Operation(summary = "Silence detected/resolved counts over time")
    public List<Map<String, Object>> timeSeriesSilence(
            @RequestParam long from, @RequestParam long to) {
        return service.getTimeSeriesSilence(from, to);
    }

    @GetMapping("/timeseries/hourly-tps")
    @Operation(summary = "Hourly TPS distribution (24 slots)")
    public List<Map<String, Object>> hourlyTps(
            @RequestParam(required = false) String clientId) {
        return service.getHourlyTpsDistribution(clientId);
    }

    @GetMapping("/timeseries/daily-amount")
    @Operation(summary = "Day-of-week amount distribution")
    public List<Map<String, Object>> dailyAmount(
            @RequestParam(required = false) String clientId) {
        return service.getDailyAmountDistribution(clientId);
    }

    // ─── STATS ────────────────────────────────────────────────

    @GetMapping("/stats/evaluations")
    @Operation(summary = "Total evaluation counts by action in range")
    public List<Map<String, Object>> statsEvaluations(
            @RequestParam long from, @RequestParam long to,
            @RequestParam(required = false) String clientId) {
        return service.getStatsEvaluations(from, to, clientId);
    }

    @GetMapping("/stats/rules")
    @Operation(summary = "Total rule trigger counts by type in range")
    public List<Map<String, Object>> statsRules(
            @RequestParam long from, @RequestParam long to,
            @RequestParam(required = false) String clientId) {
        return service.getStatsRules(from, to, clientId);
    }

    @GetMapping("/stats/notifications")
    @Operation(summary = "Total notification counts in range")
    public List<Map<String, Object>> statsNotifications(
            @RequestParam long from, @RequestParam long to) {
        return service.getStatsNotifications(from, to);
    }

    @GetMapping("/stats/silence")
    @Operation(summary = "Current silent client count")
    public List<Map<String, Object>> statsSilence() {
        return service.getStatsSilence();
    }

    @GetMapping("/stats/alerts-blocks-1h")
    @Operation(summary = "Total alerts + blocks in last hour")
    public List<Map<String, Object>> statsAlertsBlocks1h() {
        return service.getStatsAlertsBlocks1h();
    }

    @GetMapping("/stats/flagged-clients")
    @Operation(summary = "Count of clients with rule triggers in range")
    public List<Map<String, Object>> statsFlaggedClients(
            @RequestParam long from, @RequestParam long to) {
        return service.getStatsFlaggedClients(from, to);
    }

    @GetMapping("/stats/top-rule")
    @Operation(summary = "Top triggered rule in range")
    public List<Map<String, Object>> statsTopRule(
            @RequestParam long from, @RequestParam long to) {
        return service.getStatsTopRule(from, to);
    }

    @GetMapping("/stats/volume")
    @Operation(summary = "Volume insights: peak hour, peak day, daily volume")
    public List<Map<String, Object>> statsVolume() {
        return service.getStatsVolume();
    }

    // ─── TABLES ───────────────────────────────────────────────

    @GetMapping("/table/client-list")
    @Operation(summary = "All client IDs for variable dropdown")
    public List<Map<String, Object>> tableClientList() {
        return service.getTableClientList();
    }

    @GetMapping("/table/rule-breakdown")
    @Operation(summary = "Rules ranked by trigger count")
    public List<Map<String, Object>> tableRuleBreakdown(
            @RequestParam long from, @RequestParam long to) {
        return service.getTableRuleBreakdown(from, to);
    }

    @GetMapping("/table/flagged-clients")
    @Operation(summary = "Clients with rule triggers, ranked")
    public List<Map<String, Object>> tableFlaggedClients(
            @RequestParam long from, @RequestParam long to,
            @RequestParam(required = false) String ruleCategory) {
        return service.getTableFlaggedClients(from, to, ruleCategory);
    }

    @GetMapping("/table/silent-clients")
    @Operation(summary = "Currently silent clients with last transaction time")
    public List<Map<String, Object>> tableSilentClients() {
        return service.getTableSilentClients();
    }

    @GetMapping("/table/segments")
    @Operation(summary = "Client segmentation table")
    public List<Map<String, Object>> tableSegments() {
        return service.getTableSegments();
    }

    @GetMapping("/table/rail-usage")
    @Operation(summary = "Rail usage breakdown")
    public List<Map<String, Object>> tableRailUsage(
            @RequestParam(required = false) String clientId) {
        return service.getTableRailUsage(clientId);
    }

    @GetMapping("/table/campaigns")
    @Operation(summary = "Campaign recommendations")
    public List<Map<String, Object>> tableCampaigns() {
        return service.getTableCampaigns();
    }

    @GetMapping("/table/migration-opportunities")
    @Operation(summary = "Rail migration opportunities")
    public List<Map<String, Object>> tableMigrationOpportunities() {
        return service.getTableMigrationOpportunities();
    }

    // ─── PIE ──────────────────────────────────────────────────

    @GetMapping("/pie/evaluations-by-action")
    @Operation(summary = "Evaluation distribution by action")
    public List<Map<String, Object>> pieEvaluationsByAction(
            @RequestParam long from, @RequestParam long to,
            @RequestParam(required = false) String clientId) {
        return service.getPieEvaluationsByAction(from, to, clientId);
    }

    @GetMapping("/pie/segment-distribution")
    @Operation(summary = "Segment distribution counts")
    public List<Map<String, Object>> pieSegmentDistribution() {
        return service.getPieSegmentDistribution();
    }

    @GetMapping("/pie/rail-distribution")
    @Operation(summary = "Rail volume share distribution")
    public List<Map<String, Object>> pieRailDistribution() {
        return service.getPieRailDistribution();
    }

    @GetMapping("/pie/txn-type-distribution")
    @Operation(summary = "Transaction type distribution")
    public List<Map<String, Object>> pieTxnTypeDistribution(
            @RequestParam long from, @RequestParam long to,
            @RequestParam(required = false) String clientId) {
        return service.getPieTxnTypeDistribution(from, to, clientId);
    }
}
