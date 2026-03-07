package com.bank.anomaly.controller;

import com.bank.anomaly.model.NetworkGraph;
import com.bank.anomaly.model.RulePerformance;
import com.bank.anomaly.repository.AiFeedbackRepository;
import com.bank.anomaly.service.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/analytics")
@Tag(name = "Analytics", description = "Rule performance analytics and network visualization data")
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final AiFeedbackRepository aiFeedbackRepository;

    public AnalyticsController(AnalyticsService analyticsService,
                               AiFeedbackRepository aiFeedbackRepository) {
        this.analyticsService = analyticsService;
        this.aiFeedbackRepository = aiFeedbackRepository;
    }

    @GetMapping("/rules/performance")
    @Operation(summary = "Get rule performance stats",
               description = "Returns per-rule TP/FP counts and precision based on review queue feedback, optionally filtered by time range")
    public ResponseEntity<List<RulePerformance>> getRulePerformance(
            @Parameter(description = "Start time (epoch ms)")
            @RequestParam(required = false) Long fromDate,
            @Parameter(description = "End time (epoch ms)")
            @RequestParam(required = false) Long toDate) {
        return ResponseEntity.ok(analyticsService.getRulePerformanceStats(fromDate, toDate));
    }

    @GetMapping("/ai-feedback/stats")
    @Operation(summary = "Get AI explanation feedback stats",
               description = "Returns aggregate counts of helpful vs not helpful AI explanation ratings")
    public ResponseEntity<Map<String, Object>> getAiFeedbackStats() {
        return ResponseEntity.ok(aiFeedbackRepository.getStats());
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
