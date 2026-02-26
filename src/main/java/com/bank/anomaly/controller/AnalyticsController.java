package com.bank.anomaly.controller;

import com.bank.anomaly.model.NetworkGraph;
import com.bank.anomaly.model.RulePerformance;
import com.bank.anomaly.service.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/analytics")
@Tag(name = "Analytics", description = "Rule performance analytics and network visualization data")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/rules/performance")
    @Operation(summary = "Get rule performance stats",
               description = "Returns per-rule TP/FP counts and precision based on review queue feedback")
    public ResponseEntity<List<RulePerformance>> getRulePerformance() {
        return ResponseEntity.ok(analyticsService.getRulePerformanceStats());
    }

    @GetMapping("/graph/client/{clientId}/network")
    @Operation(summary = "Get client network graph",
               description = "Returns nodes and edges for beneficiary network visualization centered on a client")
    public ResponseEntity<NetworkGraph> getClientNetwork(
            @Parameter(description = "Client ID", example = "CLIENT-007")
            @PathVariable String clientId) {
        return ResponseEntity.ok(analyticsService.getClientNetwork(clientId));
    }
}
