package com.bank.anomaly.controller;

import com.bank.anomaly.model.EvaluationResult;
import com.bank.anomaly.model.PagedResponse;
import com.bank.anomaly.model.RiskLevel;
import com.bank.anomaly.model.Transaction;
import com.bank.anomaly.service.ReviewQueueService;
import com.bank.anomaly.service.TransactionEvaluationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DemoDataController.class)
class DemoDataControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TransactionEvaluationService evaluationService;

    @MockBean
    private ReviewQueueService reviewQueueService;

    @Test
    void generateDemoData_success() throws Exception {
        EvaluationResult passResult = EvaluationResult.builder()
                .txnId("T1").clientId("C1").compositeScore(10.0)
                .riskLevel(RiskLevel.LOW).action("PASS")
                .ruleResults(Collections.emptyList())
                .evaluatedAt(System.currentTimeMillis())
                .build();

        when(evaluationService.evaluate(any(Transaction.class))).thenReturn(passResult);
        when(reviewQueueService.getQueueItems(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new PagedResponse<>(List.of(), false, null));

        mockMvc.perform(post("/api/v1/demo/generate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEvaluated").isNumber())
                .andExpect(jsonPath("$.results.PASS").isNumber())
                .andExpect(jsonPath("$.results.ALERT").isNumber())
                .andExpect(jsonPath("$.results.BLOCK").isNumber())
                .andExpect(jsonPath("$.feedbackSubmitted").value(0))
                .andExpect(jsonPath("$.durationMs").isNumber());
    }

    @Test
    void generateDemoData_countsActions() throws Exception {
        EvaluationResult alertResult = EvaluationResult.builder()
                .txnId("T2").clientId("C2").compositeScore(45.0)
                .riskLevel(RiskLevel.MEDIUM).action("ALERT")
                .ruleResults(Collections.emptyList())
                .evaluatedAt(System.currentTimeMillis())
                .build();

        when(evaluationService.evaluate(any(Transaction.class))).thenReturn(alertResult);
        when(reviewQueueService.getQueueItems(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new PagedResponse<>(List.of(), false, null));

        mockMvc.perform(post("/api/v1/demo/generate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.ALERT").isNumber())
                .andExpect(jsonPath("$.totalEvaluated").isNumber());
    }
}
