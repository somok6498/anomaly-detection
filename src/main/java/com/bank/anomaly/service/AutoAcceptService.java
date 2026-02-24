package com.bank.anomaly.service;

import com.bank.anomaly.config.MetricsConfig;
import com.bank.anomaly.model.ReviewQueueItem;
import com.bank.anomaly.model.ReviewStatus;
import com.bank.anomaly.repository.ReviewQueueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class AutoAcceptService {

    private static final Logger log = LoggerFactory.getLogger(AutoAcceptService.class);

    private final ReviewQueueRepository reviewQueueRepo;
    private final MetricsConfig metricsConfig;

    public AutoAcceptService(ReviewQueueRepository reviewQueueRepo,
                              MetricsConfig metricsConfig) {
        this.reviewQueueRepo = reviewQueueRepo;
        this.metricsConfig = metricsConfig;
    }

    @Scheduled(fixedRateString = "${risk.feedback.auto-accept-check-interval-seconds:60}",
               timeUnit = TimeUnit.SECONDS,
               initialDelayString = "30")
    public void autoAcceptExpiredItems() {
        List<ReviewQueueItem> pendingItems = reviewQueueRepo.findPending();
        long now = System.currentTimeMillis();
        int autoAccepted = 0;

        for (ReviewQueueItem item : pendingItems) {
            if (item.getAutoAcceptDeadline() > 0 && item.getAutoAcceptDeadline() < now) {
                boolean updated = reviewQueueRepo.updateFeedback(
                        item.getTxnId(), ReviewStatus.AUTO_ACCEPTED, "SYSTEM");
                if (updated) {
                    autoAccepted++;
                }
            }
        }

        if (autoAccepted > 0) {
            log.info("Auto-accepted {} expired review queue items", autoAccepted);
            metricsConfig.recordAutoAccepted(autoAccepted);
        }
    }
}
