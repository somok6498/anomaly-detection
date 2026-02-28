package com.bank.anomaly.controller;

import com.bank.anomaly.model.*;
import com.bank.anomaly.repository.RuleWeightHistoryRepository;
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
import static org.mockito.Mockito.when;
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

    @Test
    void getQueueItems_success() throws Exception {
        when(reviewQueueService.getQueueItems(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(TestDataFactory.createReviewQueueItem("TXN-1", "C-1", ReviewStatus.PENDING)));

        mockMvc.perform(get("/api/v1/review/queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].txnId").value("TXN-1"));
    }

    @Test
    void getQueueItems_withFilters() throws Exception {
        when(reviewQueueService.getQueueItems(eq("ALERT"), eq("C-1"), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/review/queue?action=ALERT&clientId=C-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
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
        when(reviewQueueService.getQueueStats()).thenReturn(Map.of(
                "pending", 10, "truePositive", 5, "falsePositive", 3, "autoAccepted", 2));

        mockMvc.perform(get("/api/v1/review/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(10))
                .andExpect(jsonPath("$.truePositive").value(5));
    }

    @Test
    void getWeightHistory_success() throws Exception {
        when(weightHistoryRepo.findAll(50))
                .thenReturn(List.of(TestDataFactory.createRuleWeightChange("R1", 1.0, 1.2)));

        mockMvc.perform(get("/api/v1/review/weight-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].ruleId").value("R1"));
    }

    @Test
    void getWeightHistory_byRuleId() throws Exception {
        when(weightHistoryRepo.findByRuleId("R1", 50))
                .thenReturn(List.of(TestDataFactory.createRuleWeightChange("R1", 1.0, 1.2)));

        mockMvc.perform(get("/api/v1/review/weight-history?ruleId=R1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ruleId").value("R1"));
    }
}
