package com.bank.anomaly.controller;

import com.bank.anomaly.model.*;
import com.bank.anomaly.repository.AiFeedbackRepository;
import com.bank.anomaly.repository.RiskResultRepository;
import com.bank.anomaly.repository.RuleWeightHistoryRepository;
import com.bank.anomaly.service.ReviewQueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/review")
@Tag(name = "Review Queue", description = "Ops feedback queue for ALERT/BLOCK transactions with rule auto-tuning")
public class ReviewQueueController {

    private final ReviewQueueService reviewQueueService;
    private final RuleWeightHistoryRepository weightHistoryRepo;
    private final AiFeedbackRepository aiFeedbackRepository;
    private final RiskResultRepository riskResultRepository;

    public ReviewQueueController(ReviewQueueService reviewQueueService,
                                  RuleWeightHistoryRepository weightHistoryRepo,
                                  AiFeedbackRepository aiFeedbackRepository,
                                  RiskResultRepository riskResultRepository) {
        this.reviewQueueService = reviewQueueService;
        this.weightHistoryRepo = weightHistoryRepo;
        this.aiFeedbackRepository = aiFeedbackRepository;
        this.riskResultRepository = riskResultRepository;
    }

    @GetMapping("/queue")
    @Operation(summary = "List review queue items",
               description = "Returns ALERT/BLOCK transactions pending ops review. Supports filters and cursor-based pagination.")
    public ResponseEntity<PagedResponse<ReviewQueueItem>> getQueueItems(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String clientId,
            @RequestParam(required = false) Long fromDate,
            @RequestParam(required = false) Long toDate,
            @RequestParam(required = false) String ruleId,
            @RequestParam(required = false) String feedbackStatus,
            @RequestParam(defaultValue = "100") int limit,
            @Parameter(description = "Cursor: return records with enqueuedAt before this value")
            @RequestParam(required = false) Long before) {
        PagedResponse<ReviewQueueItem> items = reviewQueueService.getQueueItems(
                action, clientId, fromDate, toDate, ruleId, feedbackStatus, limit, before);
        return ResponseEntity.ok(items);
    }

    @GetMapping("/queue/{txnId}")
    @Operation(summary = "Get queue item detail",
               description = "Returns full detail: queue item + evaluation result + transaction + client profile")
    public ResponseEntity<?> getQueueItemDetail(@PathVariable String txnId) {
        ReviewQueueDetail detail = reviewQueueService.getQueueItemDetail(txnId);
        if (detail == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(detail);
    }

    @PostMapping("/queue/{txnId}/feedback")
    @Operation(summary = "Submit feedback for a queue item",
               description = "Mark a transaction as TRUE_POSITIVE or FALSE_POSITIVE")
    public ResponseEntity<?> submitFeedback(@PathVariable String txnId,
                                             @RequestBody Map<String, String> body) {
        String status = body.get("status");
        String feedbackBy = body.getOrDefault("feedbackBy", "ops");

        if (status == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "status is required"));
        }

        try {
            ReviewStatus reviewStatus = ReviewStatus.valueOf(status.toUpperCase());
            ReviewQueueItem updated = reviewQueueService.submitFeedback(txnId, reviewStatus, feedbackBy);
            if (updated == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/queue/bulk-feedback")
    @Operation(summary = "Submit bulk feedback",
               description = "Mark multiple transactions as TRUE_POSITIVE or FALSE_POSITIVE at once")
    public ResponseEntity<?> bulkSubmitFeedback(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> txnIds = (List<String>) body.get("txnIds");
        String status = (String) body.get("status");
        String feedbackBy = (String) body.getOrDefault("feedbackBy", "ops");

        if (txnIds == null || txnIds.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "txnIds is required and must not be empty"));
        }
        if (status == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "status is required"));
        }

        try {
            ReviewStatus reviewStatus = ReviewStatus.valueOf(status.toUpperCase());
            int updatedCount = reviewQueueService.bulkSubmitFeedback(txnIds, reviewStatus, feedbackBy);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("updatedCount", updatedCount);
            response.put("requestedCount", txnIds.size());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/stats")
    @Operation(summary = "Get review queue statistics",
               description = "Returns counts by feedback status, optionally filtered by time range")
    public ResponseEntity<Map<String, Integer>> getStats(
            @RequestParam(required = false) Long fromDate,
            @RequestParam(required = false) Long toDate) {
        return ResponseEntity.ok(reviewQueueService.getQueueStats(fromDate, toDate));
    }

    @PostMapping("/queue/{txnId}/ai-feedback")
    @Operation(summary = "Submit feedback on AI explanation",
               description = "Rate the AI-generated explanation as helpful or not helpful")
    public ResponseEntity<?> submitAiFeedback(@PathVariable String txnId,
                                               @RequestBody Map<String, Object> body) {
        Object helpfulObj = body.get("helpful");
        if (helpfulObj == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "helpful (boolean) is required"));
        }

        EvaluationResult eval = riskResultRepository.findByTxnId(txnId);
        if (eval == null) {
            return ResponseEntity.notFound().build();
        }

        boolean helpful = Boolean.parseBoolean(helpfulObj.toString());
        String operatorId = (String) body.getOrDefault("operatorId", "ops");

        AiFeedback feedback = AiFeedback.builder()
                .txnId(txnId)
                .helpful(helpful)
                .operatorId(operatorId)
                .timestamp(System.currentTimeMillis())
                .build();
        aiFeedbackRepository.save(feedback);
        return ResponseEntity.ok(feedback);
    }

    @GetMapping("/queue/{txnId}/ai-feedback")
    @Operation(summary = "Get AI explanation feedback",
               description = "Returns existing feedback for this transaction's AI explanation")
    public ResponseEntity<?> getAiFeedback(@PathVariable String txnId) {
        AiFeedback feedback = aiFeedbackRepository.findByTxnId(txnId);
        if (feedback == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(feedback);
    }

    @GetMapping("/weight-history")
    @Operation(summary = "Get rule weight change history",
               description = "Returns history of auto-tuning weight adjustments. Supports cursor-based pagination.")
    public ResponseEntity<PagedResponse<RuleWeightChange>> getWeightHistory(
            @RequestParam(required = false) String ruleId,
            @RequestParam(defaultValue = "50") int limit,
            @Parameter(description = "Cursor: return records with adjustedAt before this value")
            @RequestParam(required = false) Long before) {
        if (ruleId != null && !ruleId.isEmpty()) {
            return ResponseEntity.ok(weightHistoryRepo.findByRuleId(ruleId, limit, before));
        }
        return ResponseEntity.ok(weightHistoryRepo.findAll(limit, before));
    }
}
