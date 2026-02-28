package com.bank.anomaly.service;

import com.bank.anomaly.config.MetricsConfig;
import com.bank.anomaly.model.PagedResponse;
import com.bank.anomaly.model.*;
import com.bank.anomaly.repository.ClientProfileRepository;
import com.bank.anomaly.repository.ReviewQueueRepository;
import com.bank.anomaly.repository.RiskResultRepository;
import com.bank.anomaly.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReviewQueueService {

    private static final Logger log = LoggerFactory.getLogger(ReviewQueueService.class);

    private final ReviewQueueRepository reviewQueueRepo;
    private final RiskResultRepository riskResultRepo;
    private final TransactionRepository transactionRepo;
    private final ClientProfileRepository profileRepo;
    private final MetricsConfig metricsConfig;

    public ReviewQueueService(ReviewQueueRepository reviewQueueRepo,
                               RiskResultRepository riskResultRepo,
                               TransactionRepository transactionRepo,
                               ClientProfileRepository profileRepo,
                               MetricsConfig metricsConfig) {
        this.reviewQueueRepo = reviewQueueRepo;
        this.riskResultRepo = riskResultRepo;
        this.transactionRepo = transactionRepo;
        this.profileRepo = profileRepo;
        this.metricsConfig = metricsConfig;
    }

    public PagedResponse<ReviewQueueItem> getQueueItems(String action, String clientId,
                                                Long fromDate, Long toDate,
                                                String ruleId, int limit, Long before) {
        return reviewQueueRepo.findByFilters(action, clientId, fromDate, toDate, ruleId, limit, before);
    }

    public ReviewQueueDetail getQueueItemDetail(String txnId) {
        ReviewQueueItem queueItem = reviewQueueRepo.findByTxnId(txnId);
        if (queueItem == null) return null;

        EvaluationResult evaluation = riskResultRepo.findByTxnId(txnId);
        Transaction transaction = transactionRepo.findByTxnId(txnId);
        ClientProfile profile = profileRepo.findByClientId(queueItem.getClientId());

        return ReviewQueueDetail.builder()
                .queueItem(queueItem)
                .evaluation(evaluation)
                .transaction(transaction)
                .clientProfile(profile)
                .build();
    }

    public ReviewQueueItem submitFeedback(String txnId, ReviewStatus status, String feedbackBy) {
        if (status != ReviewStatus.TRUE_POSITIVE && status != ReviewStatus.FALSE_POSITIVE) {
            throw new IllegalArgumentException("Feedback status must be TRUE_POSITIVE or FALSE_POSITIVE");
        }

        boolean updated = reviewQueueRepo.updateFeedback(txnId, status, feedbackBy);
        if (!updated) {
            log.warn("Could not submit feedback for txn={}: item not found or already reviewed", txnId);
            return reviewQueueRepo.findByTxnId(txnId);
        }

        metricsConfig.recordFeedback(status.name());
        log.info("Feedback submitted: txn={}, status={}, by={}", txnId, status, feedbackBy);
        return reviewQueueRepo.findByTxnId(txnId);
    }

    public int bulkSubmitFeedback(List<String> txnIds, ReviewStatus status, String feedbackBy) {
        if (status != ReviewStatus.TRUE_POSITIVE && status != ReviewStatus.FALSE_POSITIVE) {
            throw new IllegalArgumentException("Feedback status must be TRUE_POSITIVE or FALSE_POSITIVE");
        }

        int updated = reviewQueueRepo.bulkUpdateFeedback(txnIds, status, feedbackBy);
        for (int i = 0; i < updated; i++) {
            metricsConfig.recordFeedback(status.name());
        }
        log.info("Bulk feedback submitted: {} items marked as {} by {}", updated, status, feedbackBy);
        return updated;
    }

    public Map<String, Integer> getQueueStats() {
        int[] counts = reviewQueueRepo.countByStatus();
        Map<String, Integer> stats = new LinkedHashMap<>();
        stats.put("pending", counts[0]);
        stats.put("truePositive", counts[1]);
        stats.put("falsePositive", counts[2]);
        stats.put("autoAccepted", counts[3]);
        return stats;
    }
}
