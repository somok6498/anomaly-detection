package com.bank.anomaly.service;

import com.bank.anomaly.config.FeedbackConfig;
import com.bank.anomaly.config.MetricsConfig;
import com.bank.anomaly.config.RiskThresholdConfig;
import com.bank.anomaly.engine.EvaluationContext;
import com.bank.anomaly.engine.RuleEngine;
import com.bank.anomaly.model.ClientProfile;
import com.bank.anomaly.model.EvaluationResult;
import com.bank.anomaly.model.ReviewQueueItem;
import com.bank.anomaly.model.ReviewStatus;
import com.bank.anomaly.model.RiskLevel;
import com.bank.anomaly.model.RuleResult;
import com.bank.anomaly.model.Transaction;
import com.bank.anomaly.repository.ReviewQueueRepository;
import com.bank.anomaly.repository.RiskResultRepository;
import com.bank.anomaly.repository.TransactionRepository;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * Main orchestrator for transaction evaluation.
 *
 * Flow:
 * 1. Load (or create) the client's behavioral profile
 * 2. Build evaluation context with live hourly counters
 * 3. Run all active rules via the RuleEngine
 * 4. Compute composite score via RiskScoringService
 * 5. Update the client profile with this transaction's data
 * 6. Persist the evaluation result
 * 7. Return the result (PASS / ALERT / BLOCK)
 */
@Service
public class TransactionEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(TransactionEvaluationService.class);

    private final ProfileService profileService;
    private final RuleEngine ruleEngine;
    private final RiskScoringService riskScoringService;
    private final RiskResultRepository riskResultRepository;
    private final TransactionRepository transactionRepository;
    private final RiskThresholdConfig thresholdConfig;
    private final TwilioNotificationService notificationService;
    private final MetricsConfig metricsConfig;
    private final ReviewQueueRepository reviewQueueRepository;
    private final FeedbackConfig feedbackConfig;

    public TransactionEvaluationService(ProfileService profileService,
                                        RuleEngine ruleEngine,
                                        RiskScoringService riskScoringService,
                                        RiskResultRepository riskResultRepository,
                                        TransactionRepository transactionRepository,
                                        RiskThresholdConfig thresholdConfig,
                                        TwilioNotificationService notificationService,
                                        MetricsConfig metricsConfig,
                                        ReviewQueueRepository reviewQueueRepository,
                                        FeedbackConfig feedbackConfig) {
        this.profileService = profileService;
        this.ruleEngine = ruleEngine;
        this.riskScoringService = riskScoringService;
        this.riskResultRepository = riskResultRepository;
        this.transactionRepository = transactionRepository;
        this.thresholdConfig = thresholdConfig;
        this.notificationService = notificationService;
        this.metricsConfig = metricsConfig;
        this.reviewQueueRepository = reviewQueueRepository;
        this.feedbackConfig = feedbackConfig;
    }

    /**
     * Evaluate a transaction for anomalies.
     * This is the main entry point called by the REST controller.
     */
    @Observed(name = "transaction.evaluate", contextualName = "evaluate-transaction")
    public EvaluationResult evaluate(Transaction txn) {
        // 0. Persist the incoming transaction
        transactionRepository.save(txn);

        // 1. Load client profile (create new if first-time client)
        ClientProfile profile = profileService.getOrCreateProfile(txn.getClientId());

        // 2. Check if client has enough history for meaningful evaluation
        if (profile.getTotalTxnCount() < thresholdConfig.getMinProfileTxns()) {
            log.debug("Client {} has insufficient history ({} txns, min {}). Passing transaction.",
                    txn.getClientId(), profile.getTotalTxnCount(), thresholdConfig.getMinProfileTxns());

            // Still update the profile to build history
            profileService.updateProfile(profile, txn);

            EvaluationResult passResult = EvaluationResult.builder()
                    .txnId(txn.getTxnId())
                    .clientId(txn.getClientId())
                    .compositeScore(0.0)
                    .riskLevel(RiskLevel.LOW)
                    .action("PASS")
                    .ruleResults(Collections.emptyList())
                    .evaluatedAt(System.currentTimeMillis())
                    .build();

            riskResultRepository.save(passResult);
            return passResult;
        }

        // 3. Build evaluation context with live hourly counters
        long currentHourlyCount = profileService.getCurrentHourlyCount(
                txn.getClientId(), txn.getTimestamp());
        long currentHourlyAmount = profileService.getCurrentHourlyAmount(
                txn.getClientId(), txn.getTimestamp());

        // Daily counters for Rules 10, 11, 13
        long currentDailyCount = profileService.getCurrentDailyCount(
                txn.getClientId(), txn.getTimestamp());
        long currentDailyAmount = profileService.getCurrentDailyAmount(
                txn.getClientId(), txn.getTimestamp());
        long currentDailyNewBene = profileService.getCurrentDailyNewBeneCount(
                txn.getClientId(), txn.getTimestamp());

        EvaluationContext.EvaluationContextBuilder ctxBuilder = EvaluationContext.builder()
                .currentHourlyTxnCount(currentHourlyCount)
                .currentHourlyAmountPaise(currentHourlyAmount)
                .currentDailyTxnCount(currentDailyCount)
                .currentDailyAmountPaise(currentDailyAmount)
                .currentDailyNewBeneficiaryCount(currentDailyNewBene);

        // Populate beneficiary window data if beneficiary info is present
        String beneKey = txn.getBeneficiaryKey();
        if (beneKey != null) {
            long beneCount = profileService.getCurrentBeneficiaryCount(
                    txn.getClientId(), beneKey, txn.getTimestamp());
            long beneAmount = profileService.getCurrentBeneficiaryAmount(
                    txn.getClientId(), beneKey, txn.getTimestamp());
            long dailyBeneAmount = profileService.getCurrentDailyBeneficiaryAmount(
                    txn.getClientId(), beneKey, txn.getTimestamp());
            ctxBuilder.currentWindowBeneficiaryTxnCount(beneCount)
                      .currentWindowBeneficiaryAmountPaise(beneAmount)
                      .currentBeneficiaryKey(beneKey)
                      .currentDailyBeneficiaryAmountPaise(dailyBeneAmount);
        }

        EvaluationContext context = ctxBuilder.build();

        // 4. Run all active rules
        List<RuleResult> ruleResults = ruleEngine.evaluateAll(txn, profile, context);

        // 5. Compute composite score and determine action
        EvaluationResult result = riskScoringService.computeResult(txn, ruleResults);

        // 6. Update client profile (after evaluation, so scoring uses pre-update state)
        profileService.updateProfile(profile, txn);

        // 7. Persist evaluation result
        riskResultRepository.save(result);

        // 7b. Enqueue for ops review if ALERT or BLOCK
        if ("ALERT".equals(result.getAction()) || "BLOCK".equals(result.getAction())) {
            List<String> triggeredRuleIds = result.getRuleResults().stream()
                    .filter(RuleResult::isTriggered)
                    .map(RuleResult::getRuleId)
                    .toList();

            long now = System.currentTimeMillis();
            ReviewQueueItem queueItem = ReviewQueueItem.builder()
                    .txnId(txn.getTxnId())
                    .clientId(txn.getClientId())
                    .action(result.getAction())
                    .compositeScore(result.getCompositeScore())
                    .riskLevel(result.getRiskLevel().name())
                    .triggeredRuleIds(triggeredRuleIds)
                    .enqueuedAt(now)
                    .feedbackStatus(ReviewStatus.PENDING)
                    .feedbackAt(0)
                    .autoAcceptDeadline(now + feedbackConfig.getAutoAcceptTimeoutMs())
                    .build();

            reviewQueueRepository.save(queueItem);
        }

        // 8. Record metrics
        metricsConfig.recordEvaluation(result.getAction(), result.getCompositeScore());

        // 9. Send notification if blocked (async — does not delay response)
        notificationService.notifyIfBlocked(txn, result);

        // 10. Log significant events
        if (result.getCompositeScore() >= thresholdConfig.getAlertThreshold()) {
            log.warn("Anomaly detected for client={}, txn={}: score={}, level={}, action={}",
                    txn.getClientId(), txn.getTxnId(),
                    result.getCompositeScore(), result.getRiskLevel(), result.getAction());
        }

        return result;
    }
}
