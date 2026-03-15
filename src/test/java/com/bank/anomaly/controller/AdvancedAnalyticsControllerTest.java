package com.bank.anomaly.controller;

import com.bank.anomaly.config.RiskThresholdConfig;
import com.bank.anomaly.engine.RuleEngine;
import com.bank.anomaly.model.EvaluationResult;
import com.bank.anomaly.model.RiskLevel;
import com.bank.anomaly.model.RuleResult;
import com.bank.anomaly.service.AdvancedAnalyticsService;
import com.bank.anomaly.service.ProfileService;
import com.bank.anomaly.service.RiskScoringService;
import com.bank.anomaly.testutil.TestDataFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdvancedAnalyticsController.class)
class AdvancedAnalyticsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AdvancedAnalyticsService advancedAnalyticsService;

    @MockBean
    private ProfileService profileService;

    @MockBean
    private RuleEngine ruleEngine;

    @MockBean
    private RiskScoringService riskScoringService;

    @MockBean
    private RiskThresholdConfig thresholdConfig;

    // ── Top Risk Clients ──────────────────────────────────────

    @Test
    void getTopRiskClients_defaultParams_success() throws Exception {
        Map<String, Object> client1 = new LinkedHashMap<>();
        client1.put("clientId", "CLIENT-006");
        client1.put("avgScore", 62.5);
        client1.put("maxScore", 85.0);
        client1.put("alertCount", 8L);
        client1.put("blockCount", 3L);
        client1.put("totalEvaluated", 50);
        client1.put("totalTransactions", 500L);

        when(advancedAnalyticsService.getTopRiskClients(10, "avgScore"))
                .thenReturn(List.of(client1));

        mockMvc.perform(get("/api/v1/advanced/top-risk-clients"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].clientId").value("CLIENT-006"))
                .andExpect(jsonPath("$[0].avgScore").value(62.5))
                .andExpect(jsonPath("$[0].blockCount").value(3));

        verify(advancedAnalyticsService).getTopRiskClients(10, "avgScore");
    }

    @Test
    void getTopRiskClients_customParams_success() throws Exception {
        when(advancedAnalyticsService.getTopRiskClients(5, "blockCount"))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/v1/advanced/top-risk-clients")
                        .param("limit", "5")
                        .param("sortBy", "blockCount"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verify(advancedAnalyticsService).getTopRiskClients(5, "blockCount");
    }

    // ── System Overview ───────────────────────────────────────

    @Test
    void getSystemOverview_success() throws Exception {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("totalClients", 11);
        overview.put("totalTransactions", 100500L);
        Map<String, Integer> queueStats = new LinkedHashMap<>();
        queueStats.put("pending", 5);
        queueStats.put("truePositive", 20);
        queueStats.put("falsePositive", 8);
        queueStats.put("autoAccepted", 12);
        overview.put("reviewQueue", queueStats);
        overview.put("silentClients", 2);

        when(advancedAnalyticsService.getSystemOverview()).thenReturn(overview);

        mockMvc.perform(get("/api/v1/advanced/system-overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClients").value(11))
                .andExpect(jsonPath("$.totalTransactions").value(100500))
                .andExpect(jsonPath("$.reviewQueue.pending").value(5))
                .andExpect(jsonPath("$.silentClients").value(2));
    }

    // ── Search Transactions ───────────────────────────────────

    @Test
    void searchTransactions_noFilters_success() throws Exception {
        Map<String, Object> txn = new LinkedHashMap<>();
        txn.put("txnId", "TXN-001");
        txn.put("clientId", "CLIENT-001");
        txn.put("txnType", "NEFT");
        txn.put("amount", 50000.0);
        txn.put("timestamp", 1700000000000L);

        when(advancedAnalyticsService.searchTransactions(
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(50)))
                .thenReturn(List.of(txn));

        mockMvc.perform(get("/api/v1/advanced/search-transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].txnId").value("TXN-001"))
                .andExpect(jsonPath("$[0].amount").value(50000.0));
    }

    @Test
    void searchTransactions_withFilters_success() throws Exception {
        when(advancedAnalyticsService.searchTransactions(
                eq(1000L), eq(2000L), eq("CLIENT-001"), eq("NEFT"),
                eq(10000.0), eq(100000.0), isNull(), eq(20)))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/v1/advanced/search-transactions")
                        .param("fromDate", "1000")
                        .param("toDate", "2000")
                        .param("clientId", "CLIENT-001")
                        .param("txnType", "NEFT")
                        .param("minAmount", "10000")
                        .param("maxAmount", "100000")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ── Simulate Transaction ──────────────────────────────────

    @Test
    void simulateTransaction_insufficientHistory_returnsPass() throws Exception {
        when(thresholdConfig.getMinProfileTxns()).thenReturn(20L);
        when(profileService.getOrCreateProfile("CLIENT-NEW"))
                .thenReturn(TestDataFactory.createClientProfile("CLIENT-NEW", 5));

        String body = objectMapper.writeValueAsString(Map.of(
                "txnId", "SIM-001",
                "clientId", "CLIENT-NEW",
                "txnType", "NEFT",
                "amount", 50000
        ));

        mockMvc.perform(post("/api/v1/advanced/simulate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulated").value(true))
                .andExpect(jsonPath("$.note").exists())
                .andExpect(jsonPath("$.result.action").value("PASS"));
    }

    @Test
    void simulateTransaction_sufficientHistory_returnsEvaluation() throws Exception {
        when(thresholdConfig.getMinProfileTxns()).thenReturn(20L);
        when(profileService.getOrCreateProfile("CLIENT-001"))
                .thenReturn(TestDataFactory.createClientProfile("CLIENT-001", 500));
        when(profileService.getCurrentHourlyCount(anyString(), anyLong())).thenReturn(5L);
        when(profileService.getCurrentHourlyAmount(anyString(), anyLong())).thenReturn(250000L);
        when(profileService.getCurrentDailyCount(anyString(), anyLong())).thenReturn(20L);
        when(profileService.getCurrentDailyAmount(anyString(), anyLong())).thenReturn(1000000L);
        when(profileService.getCurrentDailyNewBeneCount(anyString(), anyLong())).thenReturn(2L);
        when(profileService.getCurrentBeneficiaryCount(anyString(), anyString(), anyLong())).thenReturn(3L);
        when(profileService.getCurrentBeneficiaryAmount(anyString(), anyString(), anyLong())).thenReturn(150000L);
        when(profileService.getCurrentDailyBeneficiaryAmount(anyString(), anyString(), anyLong())).thenReturn(150000L);

        List<RuleResult> ruleResults = List.of(
                TestDataFactory.createRuleResult("RULE-1", true, 55.0, 1.0));
        when(ruleEngine.evaluateAll(any(), any(), any())).thenReturn(ruleResults);

        EvaluationResult evalResult = TestDataFactory.createEvaluationResult("SIM-002", "CLIENT-001", 55.0, "ALERT");
        when(riskScoringService.computeResult(any(), eq(ruleResults))).thenReturn(evalResult);

        String body = objectMapper.writeValueAsString(Map.of(
                "txnId", "SIM-002",
                "clientId", "CLIENT-001",
                "txnType", "NEFT",
                "amount", 90000,
                "beneficiaryAccount", "1234567890",
                "beneficiaryIfsc", "HDFC0001234"
        ));

        mockMvc.perform(post("/api/v1/advanced/simulate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulated").value(true))
                .andExpect(jsonPath("$.result.compositeScore").value(55.0))
                .andExpect(jsonPath("$.result.action").value("ALERT"));
    }

    // ── Anomaly Trends ────────────────────────────────────────

    @Test
    void getAnomalyTrends_defaultParams_success() throws Exception {
        Map<String, Object> trends = new LinkedHashMap<>();
        trends.put("fromDate", 1700000000000L);
        trends.put("toDate", 1700604800000L);
        trends.put("bucketSize", "1h");
        trends.put("bucketCount", 5);
        trends.put("totalAlerts", 12L);
        trends.put("totalBlocks", 3L);
        trends.put("totalEvaluated", 100);
        trends.put("trend", List.of());

        when(advancedAnalyticsService.getAnomalyTrends(isNull(), isNull(), eq("1h")))
                .thenReturn(trends);

        mockMvc.perform(get("/api/v1/advanced/anomaly-trends"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bucketSize").value("1h"))
                .andExpect(jsonPath("$.totalAlerts").value(12))
                .andExpect(jsonPath("$.totalBlocks").value(3));
    }

    @Test
    void getAnomalyTrends_customBucketSize_success() throws Exception {
        Map<String, Object> trends = new LinkedHashMap<>();
        trends.put("bucketSize", "15m");
        trends.put("bucketCount", 0);
        trends.put("trend", List.of());
        trends.put("totalAlerts", 0L);
        trends.put("totalBlocks", 0L);
        trends.put("totalEvaluated", 0);

        when(advancedAnalyticsService.getAnomalyTrends(eq(1000L), eq(2000L), eq("15m")))
                .thenReturn(trends);

        mockMvc.perform(get("/api/v1/advanced/anomaly-trends")
                        .param("fromDate", "1000")
                        .param("toDate", "2000")
                        .param("bucketSize", "15m"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bucketSize").value("15m"));
    }

    // ── Mule Candidates ───────────────────────────────────────

    @Test
    void getMuleCandidates_defaultParams_success() throws Exception {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("beneficiaryKey", "HDFC0001234:9999888877");
        candidate.put("fanIn", 4);
        candidate.put("senders", List.of("CLIENT-007", "CLIENT-008", "CLIENT-009", "CLIENT-010"));

        when(advancedAnalyticsService.getMuleCandidates(20, 2))
                .thenReturn(List.of(candidate));

        mockMvc.perform(get("/api/v1/advanced/mule-candidates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].beneficiaryKey").value("HDFC0001234:9999888877"))
                .andExpect(jsonPath("$[0].fanIn").value(4))
                .andExpect(jsonPath("$[0].senders").isArray());
    }

    @Test
    void getMuleCandidates_customParams_success() throws Exception {
        when(advancedAnalyticsService.getMuleCandidates(10, 3))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/v1/advanced/mule-candidates")
                        .param("limit", "10")
                        .param("minFanIn", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    // ── Investigation Report ──────────────────────────────────

    @Test
    void generateInvestigationReport_success() throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("clientId", "CLIENT-007");
        report.put("generatedAt", 1700000000000L);
        report.put("profile", Map.of("totalTransactions", 500L, "ewmaAmount", 45000.0));
        report.put("evaluationSummary", Map.of("totalEvaluated", 50, "alerts", 8L, "blocks", 2L));
        report.put("topTriggeredRules", List.of(Map.of("ruleId", "RULE-MULE", "triggerCount", 12)));
        report.put("beneficiaryNetwork", Map.of("totalBeneficiaries", 25, "sharedBeneficiaries", 7));
        report.put("aiNarrative", "CLIENT-007 exhibits mule network patterns.");

        when(advancedAnalyticsService.generateInvestigationReport("CLIENT-007"))
                .thenReturn(report);

        mockMvc.perform(get("/api/v1/advanced/investigation-report/CLIENT-007"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").value("CLIENT-007"))
                .andExpect(jsonPath("$.profile.totalTransactions").value(500))
                .andExpect(jsonPath("$.evaluationSummary.alerts").value(8))
                .andExpect(jsonPath("$.topTriggeredRules[0].ruleId").value("RULE-MULE"))
                .andExpect(jsonPath("$.aiNarrative").value("CLIENT-007 exhibits mule network patterns."));
    }

    @Test
    void generateInvestigationReport_clientNotFound_returnsError() throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("clientId", "UNKNOWN");
        report.put("generatedAt", 1700000000000L);
        report.put("error", "Client not found");

        when(advancedAnalyticsService.generateInvestigationReport("UNKNOWN"))
                .thenReturn(report);

        mockMvc.perform(get("/api/v1/advanced/investigation-report/UNKNOWN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value("Client not found"));
    }

    // ── Rule Correlations ─────────────────────────────────────

    @Test
    void getRuleCorrelations_success() throws Exception {
        Map<String, Object> correlations = new LinkedHashMap<>();
        correlations.put("totalEvaluationsAnalyzed", 500);
        correlations.put("uniqueRulesTriggered", 12);
        correlations.put("correlationPairs", List.of(
                Map.of("ruleA", "RULE-AMOUNT", "ruleB", "RULE-TPS",
                        "coOccurrenceCount", 45, "jaccardIndex", 0.35)
        ));
        correlations.put("ruleTriggerCounts", Map.of("RULE-AMOUNT", 120, "RULE-TPS", 80));

        when(advancedAnalyticsService.getRuleCorrelations(isNull(), isNull()))
                .thenReturn(correlations);

        mockMvc.perform(get("/api/v1/advanced/rule-correlations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEvaluationsAnalyzed").value(500))
                .andExpect(jsonPath("$.uniqueRulesTriggered").value(12))
                .andExpect(jsonPath("$.correlationPairs[0].ruleA").value("RULE-AMOUNT"))
                .andExpect(jsonPath("$.correlationPairs[0].jaccardIndex").value(0.35));
    }

    @Test
    void getRuleCorrelations_withTimeRange_success() throws Exception {
        Map<String, Object> correlations = new LinkedHashMap<>();
        correlations.put("totalEvaluationsAnalyzed", 0);
        correlations.put("uniqueRulesTriggered", 0);
        correlations.put("correlationPairs", List.of());
        correlations.put("ruleTriggerCounts", Map.of());

        when(advancedAnalyticsService.getRuleCorrelations(eq(1000L), eq(2000L)))
                .thenReturn(correlations);

        mockMvc.perform(get("/api/v1/advanced/rule-correlations")
                        .param("fromDate", "1000")
                        .param("toDate", "2000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEvaluationsAnalyzed").value(0));

        verify(advancedAnalyticsService).getRuleCorrelations(1000L, 2000L);
    }
}
