package com.bank.anomaly.controller;

import com.bank.anomaly.model.ReviewQueueDetail;
import com.bank.anomaly.model.ReviewQueueItem;
import com.bank.anomaly.model.ReviewStatus;
import com.bank.anomaly.model.RuleWeightChange;
import com.bank.anomaly.repository.RuleWeightHistoryRepository;
import com.bank.anomaly.service.ReviewQueueService;
import io.swagger.v3.oas.annotations.Operation;
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

    public ReviewQueueController(ReviewQueueService reviewQueueService,
                                  RuleWeightHistoryRepository weightHistoryRepo) {
        this.reviewQueueService = reviewQueueService;
        this.weightHistoryRepo = weightHistoryRepo;
    }

    @GetMapping("/queue")
    @Operation(summary = "List review queue items",
               description = "Returns ALERT/BLOCK transactions pending ops review. Supports filters.")
    public ResponseEntity<List<ReviewQueueItem>> getQueueItems(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String clientId,
            @RequestParam(required = false) Long fromDate,
            @RequestParam(required = false) Long toDate,
            @RequestParam(required = false) String ruleId,
            @RequestParam(defaultValue = "100") int limit) {
        List<ReviewQueueItem> items = reviewQueueService.getQueueItems(
                action, clientId, fromDate, toDate, ruleId, limit);
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
               description = "Returns counts by feedback status")
    public ResponseEntity<Map<String, Integer>> getStats() {
        return ResponseEntity.ok(reviewQueueService.getQueueStats());
    }

    @GetMapping("/weight-history")
    @Operation(summary = "Get rule weight change history",
               description = "Returns history of auto-tuning weight adjustments. Optionally filter by ruleId.")
    public ResponseEntity<List<RuleWeightChange>> getWeightHistory(
            @RequestParam(required = false) String ruleId,
            @RequestParam(defaultValue = "50") int limit) {
        if (ruleId != null && !ruleId.isEmpty()) {
            return ResponseEntity.ok(weightHistoryRepo.findByRuleId(ruleId, limit));
        }
        return ResponseEntity.ok(weightHistoryRepo.findAll(limit));
    }
}
