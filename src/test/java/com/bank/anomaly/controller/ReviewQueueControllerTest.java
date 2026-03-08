package com.bank.anomaly.controller;

import com.bank.anomaly.model.*;
import com.bank.anomaly.repository.AiFeedbackRepository;
import com.bank.anomaly.repository.RiskResultRepository;
import com.bank.anomaly.repository.RuleWeightHistoryRepository;
import com.bank.anomaly.service.OllamaService;
import com.bank.anomaly.service.ReviewQueueService;
import com.bank.anomaly.testutil.TestDataFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ReviewQueueController.class)
class ReviewQueueControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ReviewQueueService reviewQueueService;

    @MockBean
    private RuleWeightHistoryRepository weightHistoryRepo;

    @MockBean
    private AiFeedbackRepository aiFeedbackRepository;

    @MockBean
    private RiskResultRepository riskResultRepository;

    @MockBean
    private OllamaService ollamaService;

    @Test
    void getQueueItems_success() throws Exception {
        PagedResponse<ReviewQueueItem> pagedResponse = new PagedResponse<>(
                List.of(TestDataFactory.createReviewQueueItem("TXN-1", "C-1", ReviewStatus.PENDING)),
                false, null);
        when(reviewQueueService.getQueueItems(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(pagedResponse);

        mockMvc.perform(get("/api/v1/review/queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].txnId").value("TXN-1"))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void getQueueItems_withFilters() throws Exception {
        PagedResponse<ReviewQueueItem> pagedResponse = new PagedResponse<>(List.of(), false, null);
        when(reviewQueueService.getQueueItems(eq("ALERT"), eq("C-1"), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(pagedResponse);

        mockMvc.perform(get("/api/v1/review/queue?action=ALERT&clientId=C-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void getQueueItemDetail_found() throws Exception {
        ReviewQueueDetail detail = TestDataFactory.createReviewQueueDetail("TXN-1", "C-1");
        when(reviewQueueService.getQueueItemDetail("TXN-1")).thenReturn(detail);

        mockMvc.perform(get("/api/v1/review/queue/TXN-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueItem.txnId").value("TXN-1"))
                .andExpect(jsonPath("$.evaluation").exists())
                .andExpect(jsonPath("$.transaction").exists());
    }

    @Test
    void getQueueItemDetail_notFound() throws Exception {
        when(reviewQueueService.getQueueItemDetail("MISSING")).thenReturn(null);

        mockMvc.perform(get("/api/v1/review/queue/MISSING"))
                .andExpect(status().isNotFound());
    }

    @Test
    void submitFeedback_success() throws Exception {
        ReviewQueueItem updated = TestDataFactory.createReviewQueueItem("TXN-1", "C-1", ReviewStatus.TRUE_POSITIVE);
        when(reviewQueueService.submitFeedback(eq("TXN-1"), eq(ReviewStatus.TRUE_POSITIVE), eq("ops")))
                .thenReturn(updated);

        mockMvc.perform(post("/api/v1/review/queue/TXN-1/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "TRUE_POSITIVE", "feedbackBy", "ops"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feedbackStatus").value("TRUE_POSITIVE"));
    }

    @Test
    void submitFeedback_badRequest_missingStatus() throws Exception {
        mockMvc.perform(post("/api/v1/review/queue/TXN-1/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("feedbackBy", "ops"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("status is required"));
    }

    @Test
    void submitFeedback_badRequest_invalidStatus() throws Exception {
        mockMvc.perform(post("/api/v1/review/queue/TXN-1/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "INVALID_VALUE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void bulkSubmitFeedback_success() throws Exception {
        when(reviewQueueService.bulkSubmitFeedback(anyList(), eq(ReviewStatus.FALSE_POSITIVE), eq("ops")))
                .thenReturn(3);

        mockMvc.perform(post("/api/v1/review/queue/bulk-feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "txnIds", List.of("T1", "T2", "T3"),
                                "status", "FALSE_POSITIVE",
                                "feedbackBy", "ops"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(3))
                .andExpect(jsonPath("$.requestedCount").value(3));
    }

    @Test
    void bulkSubmitFeedback_badRequest_emptyTxnIds() throws Exception {
        mockMvc.perform(post("/api/v1/review/queue/bulk-feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "txnIds", List.of(),
                                "status", "TRUE_POSITIVE"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getStats_success() throws Exception {
        when(reviewQueueService.getQueueStats(any(), any())).thenReturn(Map.of(
                "pending", 10, "truePositive", 5, "falsePositive", 3, "autoAccepted", 2));

        mockMvc.perform(get("/api/v1/review/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(10))
                .andExpect(jsonPath("$.truePositive").value(5));
    }

    // ── AI Feedback Tests ──

    @Test
    void submitAiFeedback_success() throws Exception {
        EvaluationResult eval = TestDataFactory.createEvaluationResult("TXN-1", "C-1", 55.0, "ALERT");
        when(riskResultRepository.findByTxnId("TXN-1")).thenReturn(eval);

        mockMvc.perform(post("/api/v1/review/queue/TXN-1/ai-feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("helpful", true, "operatorId", "ops"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.txnId").value("TXN-1"))
                .andExpect(jsonPath("$.helpful").value(true))
                .andExpect(jsonPath("$.operatorId").value("ops"));

        verify(aiFeedbackRepository).save(any(AiFeedback.class));
    }

    @Test
    void submitAiFeedback_missingHelpful_badRequest() throws Exception {
        mockMvc.perform(post("/api/v1/review/queue/TXN-1/ai-feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("operatorId", "ops"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void submitAiFeedback_txnNotFound() throws Exception {
        when(riskResultRepository.findByTxnId("MISSING")).thenReturn(null);

        mockMvc.perform(post("/api/v1/review/queue/MISSING/ai-feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("helpful", true))))
                .andExpect(status().isNotFound());
    }

    @Test
    void getAiFeedback_found() throws Exception {
        AiFeedback feedback = AiFeedback.builder()
                .txnId("TXN-1").helpful(true).operatorId("ops").timestamp(12345L).build();
        when(aiFeedbackRepository.findByTxnId("TXN-1")).thenReturn(feedback);

        mockMvc.perform(get("/api/v1/review/queue/TXN-1/ai-feedback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.txnId").value("TXN-1"))
                .andExpect(jsonPath("$.helpful").value(true));
    }

    @Test
    void getAiFeedback_notFound() throws Exception {
        when(aiFeedbackRepository.findByTxnId("MISSING")).thenReturn(null);

        mockMvc.perform(get("/api/v1/review/queue/MISSING/ai-feedback"))
                .andExpect(status().isNotFound());
    }

    // ── Weight History Tests ──

    @Test
    void getWeightHistory_success() throws Exception {
        PagedResponse<RuleWeightChange> pagedResponse = new PagedResponse<>(
                List.of(TestDataFactory.createRuleWeightChange("R1", 1.0, 1.2)),
                false, null);
        when(weightHistoryRepo.findAll(eq(50), isNull()))
                .thenReturn(pagedResponse);

        mockMvc.perform(get("/api/v1/review/weight-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].ruleId").value("R1"))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void getWeightHistory_byRuleId() throws Exception {
        PagedResponse<RuleWeightChange> pagedResponse = new PagedResponse<>(
                List.of(TestDataFactory.createRuleWeightChange("R1", 1.0, 1.2)),
                false, null);
        when(weightHistoryRepo.findByRuleId(eq("R1"), eq(50), isNull()))
                .thenReturn(pagedResponse);

        mockMvc.perform(get("/api/v1/review/weight-history?ruleId=R1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].ruleId").value("R1"));
    }

    // ── Smart Alert Triage Tests ──

    @Test
    void getAlertTriage_success() throws Exception {
        PagedResponse<ReviewQueueItem> pending = new PagedResponse<>(
                List.of(
                        TestDataFactory.createReviewQueueItem("TXN-1", "C-1", ReviewStatus.PENDING),
                        TestDataFactory.createReviewQueueItem("TXN-2", "C-2", ReviewStatus.PENDING)),
                false, null);
        when(reviewQueueService.getQueueItems(isNull(), isNull(), isNull(), isNull(), isNull(), eq("PENDING"), eq(15), isNull()))
                .thenReturn(pending);

        EvaluationResult eval1 = TestDataFactory.createEvaluationResult("TXN-1", "C-1", 75.0, "ALERT");
        EvaluationResult eval2 = TestDataFactory.createEvaluationResult("TXN-2", "C-2", 45.0, "ALERT");
        when(riskResultRepository.findByTxnId("TXN-1")).thenReturn(eval1);
        when(riskResultRepository.findByTxnId("TXN-2")).thenReturn(eval2);

        String triageJson = "[{\"txnId\":\"TXN-1\",\"rank\":1,\"urgency\":\"CRITICAL\",\"reasoning\":\"High score\"}]";
        when(ollamaService.generateAlertTriage(anyList(), anyMap())).thenReturn(triageJson);

        mockMvc.perform(get("/api/v1/review/queue/triage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.triage").exists())
                .andExpect(jsonPath("$.itemCount").value(2));
    }

    @Test
    void getAlertTriage_noPendingItems() throws Exception {
        PagedResponse<ReviewQueueItem> empty = new PagedResponse<>(List.of(), false, null);
        when(reviewQueueService.getQueueItems(isNull(), isNull(), isNull(), isNull(), isNull(), eq("PENDING"), eq(15), isNull()))
                .thenReturn(empty);

        mockMvc.perform(get("/api/v1/review/queue/triage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("No pending items to triage"));
    }

    @Test
    void getAlertTriage_ollamaUnavailable() throws Exception {
        PagedResponse<ReviewQueueItem> pending = new PagedResponse<>(
                List.of(TestDataFactory.createReviewQueueItem("TXN-1", "C-1", ReviewStatus.PENDING)),
                false, null);
        when(reviewQueueService.getQueueItems(isNull(), isNull(), isNull(), isNull(), isNull(), eq("PENDING"), eq(15), isNull()))
                .thenReturn(pending);
        when(riskResultRepository.findByTxnId("TXN-1"))
                .thenReturn(TestDataFactory.createEvaluationResult("TXN-1", "C-1", 55.0, "ALERT"));
        when(ollamaService.generateAlertTriage(anyList(), anyMap())).thenReturn(null);

        mockMvc.perform(get("/api/v1/review/queue/triage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Unable to generate triage. The AI service may be unavailable."));
    }
}
