package com.bank.anomaly.controller;

import com.bank.anomaly.service.BusinessInsightsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/insights")
@Tag(name = "Business Insights", description = "Client segmentation, rail migration intelligence, campaign recommendations, and volume analytics")
public class BusinessInsightsController {

    private final BusinessInsightsService insightsService;

    public BusinessInsightsController(BusinessInsightsService insightsService) {
        this.insightsService = insightsService;
    }

    // ─── Segmentation ─────────────────────────────────────────

    @GetMapping("/segments")
    @Operation(summary = "Get all clients with their segment classification")
    public ResponseEntity<List<Map<String, Object>>> getClientSegmentation() {
        return ResponseEntity.ok(insightsService.getClientSegmentation());
    }

    @GetMapping("/segments/summary")
    @Operation(summary = "Get segment distribution summary with counts and aggregate metrics")
    public ResponseEntity<Map<String, Object>> getSegmentSummary() {
        return ResponseEntity.ok(insightsService.getSegmentSummary());
    }

    // ─── Rail Insights ────────────────────────────────────────

    @GetMapping("/rails")
    @Operation(summary = "System-wide rail usage distribution, volume share, and characteristics")
    public ResponseEntity<Map<String, Object>> getSystemRailInsights() {
        return ResponseEntity.ok(insightsService.getSystemRailInsights());
    }

    @GetMapping("/rails/client/{clientId}")
    @Operation(summary = "Rail usage breakdown and migration opportunities for a specific client")
    public ResponseEntity<Map<String, Object>> getClientRailProfile(@PathVariable String clientId) {
        return ResponseEntity.ok(insightsService.getClientRailProfile(clientId.toUpperCase()));
    }

    @GetMapping("/rails/migration-opportunities")
    @Operation(summary = "All rail migration opportunities ranked by impact score")
    public ResponseEntity<List<Map<String, Object>>> getRailMigrationOpportunities() {
        return ResponseEntity.ok(insightsService.getRailMigrationOpportunities());
    }

    // ─── Campaign Intelligence ────────────────────────────────

    @GetMapping("/campaigns")
    @Operation(summary = "Auto-generated campaign recommendations with target client lists")
    public ResponseEntity<List<Map<String, Object>>> getCampaignRecommendations() {
        return ResponseEntity.ok(insightsService.getCampaignRecommendations());
    }

    // ─── Volume & Revenue ─────────────────────────────────────

    @GetMapping("/volume")
    @Operation(summary = "System-wide volume insights: peak hours, day-of-week patterns, throughput")
    public ResponseEntity<Map<String, Object>> getVolumeInsights() {
        return ResponseEntity.ok(insightsService.getVolumeInsights());
    }

    // ─── Full Client BI Profile ───────────────────────────────

    @GetMapping("/client/{clientId}")
    @Operation(summary = "Complete business insight profile for a client: segment, rails, risk, campaigns")
    public ResponseEntity<Map<String, Object>> getClientInsightProfile(@PathVariable String clientId) {
        return ResponseEntity.ok(insightsService.getClientInsightProfile(clientId.toUpperCase()));
    }
}
