package com.bank.anomaly.controller;

import com.bank.anomaly.config.RiskThresholdConfig;
import com.bank.anomaly.engine.EvaluationContext;
import com.bank.anomaly.engine.RuleEngine;
import com.bank.anomaly.model.ClientProfile;
import com.bank.anomaly.model.EvaluationResult;
import com.bank.anomaly.model.RiskLevel;
import com.bank.anomaly.model.RuleResult;
import com.bank.anomaly.model.Transaction;
import com.bank.anomaly.service.AdvancedAnalyticsService;
import com.bank.anomaly.service.ProfileService;
import com.bank.anomaly.service.RiskScoringService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/advanced")
@Tag(name = "Advanced Analytics", description = "Top risk clients, anomaly trends, mule candidates, investigation reports, transaction search, simulation, and rule correlations")
public class AdvancedAnalyticsController {

    private final AdvancedAnalyticsService advancedAnalyticsService;
    private final ProfileService profileService;
    private final RuleEngine ruleEngine;
    private final RiskScoringService riskScoringService;
    private final RiskThresholdConfig thresholdConfig;

    public AdvancedAnalyticsController(AdvancedAnalyticsService advancedAnalyticsService,
                                       ProfileService profileService,
                                       RuleEngine ruleEngine,
                                       RiskScoringService riskScoringService,
                                       RiskThresholdConfig thresholdConfig) {
        this.advancedAnalyticsService = advancedAnalyticsService;
        this.profileService = profileService;
        this.ruleEngine = ruleEngine;
        this.riskScoringService = riskScoringService;
        this.thresholdConfig = thresholdConfig;
    }

    @GetMapping("/top-risk-clients")
    @Operation(summary = "Get top risk clients ranked by score/alert/block count")
    public ResponseEntity<List<Map<String, Object>>> getTopRiskClients(
            @Parameter(description = "Number of clients to return") @RequestParam(defaultValue = "10") int limit,
            @Parameter(description = "Sort by: avgScore, maxScore, blockCount, alertCount") @RequestParam(defaultValue = "avgScore") String sortBy) {
        return ResponseEntity.ok(advancedAnalyticsService.getTopRiskClients(limit, sortBy));
    }

    @GetMapping("/system-overview")
    @Operation(summary = "Get system-wide overview: client count, queue depth, graph status, silent clients")
    public ResponseEntity<Map<String, Object>> getSystemOverview() {
        return ResponseEntity.ok(advancedAnalyticsService.getSystemOverview());
    }

    @GetMapping("/search-transactions")
    @Operation(summary = "Search transactions across all clients with filters")
    public ResponseEntity<List<Map<String, Object>>> searchTransactions(
            @RequestParam(required = false) Long fromDate,
            @RequestParam(required = false) Long toDate,
            @RequestParam(required = false) String clientId,
            @RequestParam(required = false) String txnType,
            @RequestParam(required = false) Double minAmount,
            @RequestParam(required = false) Double maxAmount,
            @RequestParam(required = false) String beneficiaryAccount,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(advancedAnalyticsService.searchTransactions(
                fromDate, toDate, clientId, txnType, minAmount, maxAmount, beneficiaryAccount, limit));
    }

    @PostMapping("/simulate")
    @Operation(summary = "Simulate a transaction evaluation without persisting (dry-run)")
    public ResponseEntity<?> simulateTransaction(@RequestBody Transaction txn) {
        // Load profile (don't create if missing)
        ClientProfile profile = profileService.getOrCreateProfile(txn.getClientId());

        if (profile.getTotalTxnCount() < thresholdConfig.getMinProfileTxns()) {
            EvaluationResult passResult = EvaluationResult.builder()
                    .txnId(txn.getTxnId() != null ? txn.getTxnId() : "SIM-" + System.currentTimeMillis())
                    .clientId(txn.getClientId())
                    .compositeScore(0.0)
                    .riskLevel(RiskLevel.LOW)
                    .action("PASS")
                    .ruleResults(Collections.emptyList())
                    .evaluatedAt(System.currentTimeMillis())
                    .build();
            return ResponseEntity.ok(Map.of("simulated", true, "result", passResult,
                    "note", "Client has insufficient history for meaningful evaluation"));
        }

        long ts = txn.getTimestamp() > 0 ? txn.getTimestamp() : System.currentTimeMillis();

        // Build context (read-only — no counters are incremented)
        long hourlyCount = profileService.getCurrentHourlyCount(txn.getClientId(), ts);
        long hourlyAmount = profileService.getCurrentHourlyAmount(txn.getClientId(), ts);
        long dailyCount = profileService.getCurrentDailyCount(txn.getClientId(), ts);
        long dailyAmount = profileService.getCurrentDailyAmount(txn.getClientId(), ts);
        long dailyNewBene = profileService.getCurrentDailyNewBeneCount(txn.getClientId(), ts);

        EvaluationContext.EvaluationContextBuilder ctxBuilder = EvaluationContext.builder()
                .currentHourlyTxnCount(hourlyCount)
                .currentHourlyAmountPaise(hourlyAmount)
                .currentDailyTxnCount(dailyCount)
                .currentDailyAmountPaise(dailyAmount)
                .currentDailyNewBeneficiaryCount(dailyNewBene)
                .currentHourOfDaySlot(ProfileService.getHourOfDaySlot(ts))
                .currentDayOfWeekSlot(ProfileService.getDayOfWeekSlot(ts));

        String beneKey = txn.getBeneficiaryKey();
        if (beneKey != null) {
            long beneCount = profileService.getCurrentBeneficiaryCount(txn.getClientId(), beneKey, ts);
            long beneAmount = profileService.getCurrentBeneficiaryAmount(txn.getClientId(), beneKey, ts);
            long dailyBeneAmount = profileService.getCurrentDailyBeneficiaryAmount(txn.getClientId(), beneKey, ts);
            ctxBuilder.currentWindowBeneficiaryTxnCount(beneCount)
                      .currentWindowBeneficiaryAmountPaise(beneAmount)
                      .currentBeneficiaryKey(beneKey)
                      .currentDailyBeneficiaryAmountPaise(dailyBeneAmount);
        }

        // Run rules (read-only)
        List<RuleResult> ruleResults = ruleEngine.evaluateAll(txn, profile, ctxBuilder.build());
        EvaluationResult result = riskScoringService.computeResult(txn, ruleResults);

        // Nothing is persisted — no profile update, no DB write, no queue entry
        return ResponseEntity.ok(Map.of("simulated", true, "result", result));
    }

    @GetMapping("/anomaly-trends")
    @Operation(summary = "Get anomaly count/score trends over time buckets")
    public ResponseEntity<Map<String, Object>> getAnomalyTrends(
            @RequestParam(required = false) Long fromDate,
            @RequestParam(required = false) Long toDate,
            @Parameter(description = "Bucket size: 15m, 1h, 6h, 1d") @RequestParam(defaultValue = "1h") String bucketSize) {
        return ResponseEntity.ok(advancedAnalyticsService.getAnomalyTrends(fromDate, toDate, bucketSize));
    }

    @GetMapping("/mule-candidates")
    @Operation(summary = "Get top beneficiaries ranked by fan-in (potential mule accounts)")
    public ResponseEntity<List<Map<String, Object>>> getMuleCandidates(
            @RequestParam(defaultValue = "20") int limit,
            @Parameter(description = "Minimum fan-in to qualify") @RequestParam(defaultValue = "2") int minFanIn) {
        return ResponseEntity.ok(advancedAnalyticsService.getMuleCandidates(limit, minFanIn));
    }

    @GetMapping("/investigation-report/{clientId}")
    @Operation(summary = "Generate a comprehensive investigation report for a client")
    public ResponseEntity<Map<String, Object>> generateInvestigationReport(
            @PathVariable String clientId) {
        return ResponseEntity.ok(advancedAnalyticsService.generateInvestigationReport(clientId));
    }

    @GetMapping("/rule-correlations")
    @Operation(summary = "Get rule co-occurrence matrix with Jaccard similarity")
    public ResponseEntity<Map<String, Object>> getRuleCorrelations(
            @RequestParam(required = false) Long fromDate,
            @RequestParam(required = false) Long toDate) {
        return ResponseEntity.ok(advancedAnalyticsService.getRuleCorrelations(fromDate, toDate));
    }
}
