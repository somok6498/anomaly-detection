package com.bank.anomaly.controller;

import com.bank.anomaly.model.ClientProfile;
import com.bank.anomaly.model.EvaluationResult;
import com.bank.anomaly.model.NetworkGraph;
import com.bank.anomaly.model.PagedResponse;
import com.bank.anomaly.model.RulePerformance;
import com.bank.anomaly.repository.AiFeedbackRepository;
import com.bank.anomaly.repository.ClientProfileRepository;
import com.bank.anomaly.repository.RiskResultRepository;
import com.bank.anomaly.service.AnalyticsService;
import com.bank.anomaly.service.OllamaService;
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
    private final ClientProfileRepository clientProfileRepository;
    private final RiskResultRepository riskResultRepository;
    private final OllamaService ollamaService;

    public AnalyticsController(AnalyticsService analyticsService,
                               AiFeedbackRepository aiFeedbackRepository,
                               ClientProfileRepository clientProfileRepository,
                               RiskResultRepository riskResultRepository,
                               OllamaService ollamaService) {
        this.analyticsService = analyticsService;
        this.aiFeedbackRepository = aiFeedbackRepository;
        this.clientProfileRepository = clientProfileRepository;
        this.riskResultRepository = riskResultRepository;
        this.ollamaService = ollamaService;
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

    @GetMapping("/client/{clientId}/narrative")
    @Operation(summary = "Generate AI risk narrative for a client",
               description = "Uses LLM to generate a plain-English behavioral summary and risk assessment for a client based on their profile and recent transactions")
    public ResponseEntity<Map<String, String>> getClientNarrative(
            @Parameter(description = "Client ID", example = "CLIENT-001")
            @PathVariable String clientId) {
        ClientProfile profile = clientProfileRepository.findByClientId(clientId);
        if (profile == null) {
            return ResponseEntity.notFound().build();
        }

        // Get recent evaluations for this client (up to 20)
        PagedResponse<EvaluationResult> evals = riskResultRepository.findByClientId(clientId, 20, null);
        List<EvaluationResult> recentEvals = evals != null ? evals.data() : List.of();

        String narrative = ollamaService.generateClientNarrative(profile, recentEvals);
        if (narrative == null) {
            return ResponseEntity.ok(Map.of("narrative", "Unable to generate narrative. The AI service may be unavailable."));
        }
        return ResponseEntity.ok(Map.of("narrative", narrative, "clientId", clientId));
    }
}
