package com.bank.anomaly.service;

import com.bank.anomaly.config.FeedbackConfig;
import com.bank.anomaly.config.MetricsConfig;
import com.bank.anomaly.model.AnomalyRule;
import com.bank.anomaly.model.ReviewQueueItem;
import com.bank.anomaly.model.ReviewStatus;
import com.bank.anomaly.model.RuleWeightChange;
import com.bank.anomaly.repository.ReviewQueueRepository;
import com.bank.anomaly.repository.RuleRepository;
import com.bank.anomaly.repository.RuleWeightHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class RuleAutoTuningService {

    private static final Logger log = LoggerFactory.getLogger(RuleAutoTuningService.class);

    private final ReviewQueueRepository reviewQueueRepo;
    private final RuleRepository ruleRepo;
    private final RuleWeightHistoryRepository historyRepo;
    private final FeedbackConfig feedbackConfig;
    private final MetricsConfig metricsConfig;

    public RuleAutoTuningService(ReviewQueueRepository reviewQueueRepo,
                                  RuleRepository ruleRepo,
                                  RuleWeightHistoryRepository historyRepo,
                                  FeedbackConfig feedbackConfig,
                                  MetricsConfig metricsConfig) {
        this.reviewQueueRepo = reviewQueueRepo;
        this.ruleRepo = ruleRepo;
        this.historyRepo = historyRepo;
        this.feedbackConfig = feedbackConfig;
        this.metricsConfig = metricsConfig;
    }

    @Scheduled(fixedRateString = "${risk.feedback.tuning-interval-hours:6}",
               timeUnit = TimeUnit.HOURS,
               initialDelayString = "1")
    public void tuneRuleWeights() {
        log.info("Starting rule auto-tuning cycle...");

        // 1. Get all items with explicit feedback (TP or FP only, exclude AUTO_ACCEPTED)
        List<ReviewQueueItem> feedbackItems = reviewQueueRepo.findAllWithFeedback();

        if (feedbackItems.isEmpty()) {
            log.info("No feedback items found. Skipping tuning cycle.");
            return;
        }

        // 2. Build per-rule TP/FP counts
        // ruleId -> [tpCount, fpCount]
        Map<String, int[]> ruleStats = new HashMap<>();

        for (ReviewQueueItem item : feedbackItems) {
            if (item.getTriggeredRuleIds() == null) continue;

            for (String ruleId : item.getTriggeredRuleIds()) {
                int[] counts = ruleStats.computeIfAbsent(ruleId, k -> new int[]{0, 0});
                if (item.getFeedbackStatus() == ReviewStatus.TRUE_POSITIVE) {
                    counts[0]++;
                } else if (item.getFeedbackStatus() == ReviewStatus.FALSE_POSITIVE) {
                    counts[1]++;
                }
            }
        }

        // 3. For each rule, apply tuning if sufficient samples
        int adjusted = 0;
        for (Map.Entry<String, int[]> entry : ruleStats.entrySet()) {
            String ruleId = entry.getKey();
            int tp = entry.getValue()[0];
            int fp = entry.getValue()[1];
            int total = tp + fp;

            if (total < feedbackConfig.getMinSamplesForTuning()) {
                log.debug("Rule {} has only {} feedback items (min {}). Skipping.",
                        ruleId, total, feedbackConfig.getMinSamplesForTuning());
                continue;
            }

            AnomalyRule rule = ruleRepo.findById(ruleId);
            if (rule == null || !rule.isEnabled()) continue;

            double tpRatio = (double) tp / total;
            double oldWeight = rule.getRiskWeight();

            // Compute adjustment: tpRatio > 0.5 = rule is accurate (increase weight)
            // tpRatio < 0.5 = too many false positives (decrease weight)
            double adjustFactor = (tpRatio - 0.5) * 2.0; // range: -1.0 to +1.0
            double maxAdj = feedbackConfig.getMaxAdjustmentPct();
            double clampedAdj = Math.max(-maxAdj, Math.min(maxAdj, adjustFactor * maxAdj));

            double newWeight = oldWeight * (1.0 + clampedAdj);

            // Apply floor and ceiling guardrails
            newWeight = Math.max(feedbackConfig.getWeightFloor(), newWeight);
            newWeight = Math.min(feedbackConfig.getWeightCeiling(), newWeight);
            newWeight = Math.round(newWeight * 1000.0) / 1000.0; // 3 decimal places

            if (Math.abs(newWeight - oldWeight) < 0.001) {
                log.debug("Rule {} weight unchanged ({}).", ruleId, oldWeight);
                continue;
            }

            // 4. Persist weight change
            rule.setRiskWeight(newWeight);
            ruleRepo.save(rule);

            // 5. Log history
            RuleWeightChange change = RuleWeightChange.builder()
                    .ruleId(ruleId)
                    .oldWeight(oldWeight)
                    .newWeight(newWeight)
                    .tpCount(tp)
                    .fpCount(fp)
                    .tpFpRatio(Math.round(tpRatio * 1000.0) / 1000.0)
                    .adjustedAt(System.currentTimeMillis())
                    .build();
            historyRepo.save(change);

            metricsConfig.recordWeightAdjustment(ruleId);
            log.info("Rule {} weight adjusted: {} -> {} (TP={}, FP={}, ratio={})",
                    ruleId, oldWeight, newWeight, tp, fp, String.format("%.3f", tpRatio));
            adjusted++;
        }

        log.info("Rule auto-tuning cycle complete. {} rules adjusted out of {} with feedback.",
                adjusted, ruleStats.size());
    }
}
