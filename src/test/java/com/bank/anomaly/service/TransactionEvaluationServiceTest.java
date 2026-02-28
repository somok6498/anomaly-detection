package com.bank.anomaly.service;

import com.bank.anomaly.config.FeedbackConfig;
import com.bank.anomaly.config.MetricsConfig;
import com.bank.anomaly.config.RiskThresholdConfig;
import com.bank.anomaly.engine.RuleEngine;
import com.bank.anomaly.model.*;
import com.bank.anomaly.repository.ReviewQueueRepository;
import com.bank.anomaly.repository.RiskResultRepository;
import com.bank.anomaly.repository.TransactionRepository;
import com.bank.anomaly.testutil.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionEvaluationServiceTest {

    @Mock private ProfileService profileService;
    @Mock private RuleEngine ruleEngine;
    @Mock private RiskScoringService riskScoringService;
    @Mock private RiskResultRepository riskResultRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private TwilioNotificationService notificationService;
    @Mock private MetricsConfig metricsConfig;
    @Mock private ReviewQueueRepository reviewQueueRepository;

    private TransactionEvaluationService evaluationService;
    private RiskThresholdConfig thresholdConfig;
    private FeedbackConfig feedbackConfig;

    @BeforeEach
    void setUp() {
        thresholdConfig = new RiskThresholdConfig();
        thresholdConfig.setMinProfileTxns(20);
        thresholdConfig.setAlertThreshold(30.0);
        thresholdConfig.setBlockThreshold(70.0);

        feedbackConfig = new FeedbackConfig();

        evaluationService = new TransactionEvaluationService(
                profileService, ruleEngine, riskScoringService,
                riskResultRepository, transactionRepository,
                thresholdConfig, notificationService, metricsConfig,
                reviewQueueRepository, feedbackConfig);
    }

    @Test
    void evaluate_newClientBelowMinTxns_passesWithoutRuleEvaluation() {
        Transaction txn = TestDataFactory.createTransaction("TXN-1", "C-NEW", "NEFT", 50000);
        ClientProfile newProfile = TestDataFactory.createClientProfile("C-NEW", 5); // below minProfileTxns=20

        when(profileService.getOrCreateProfile("C-NEW")).thenReturn(newProfile);

        EvaluationResult result = evaluationService.evaluate(txn);

        assertThat(result.getAction()).isEqualTo("PASS");
        assertThat(result.getCompositeScore()).isEqualTo(0.0);
        verify(ruleEngine, never()).evaluateAll(any(), any(), any());
        verify(profileService).updateProfile(eq(newProfile), eq(txn));
        verify(riskResultRepository).save(any(EvaluationResult.class));
    }

    @Test
    void evaluate_sufficientHistory_fullPipeline() {
        Transaction txn = TestDataFactory.createTransaction("TXN-1", "C-1", "NEFT", 50000);
        ClientProfile profile = TestDataFactory.createClientProfile("C-1", 100);
        List<RuleResult> ruleResults = List.of(TestDataFactory.createRuleResult("R1", true, 45.0, 1.0));
        EvaluationResult evalResult = TestDataFactory.createEvaluationResult("TXN-1", "C-1", 45.0, "ALERT");

        when(profileService.getOrCreateProfile("C-1")).thenReturn(profile);
        when(profileService.getCurrentHourlyCount(anyString(), anyLong())).thenReturn(5L);
        when(profileService.getCurrentHourlyAmount(anyString(), anyLong())).thenReturn(250000L);
        when(profileService.getCurrentDailyCount(anyString(), anyLong())).thenReturn(20L);
        when(profileService.getCurrentDailyAmount(anyString(), anyLong())).thenReturn(1000000L);
        when(profileService.getCurrentDailyNewBeneCount(anyString(), anyLong())).thenReturn(1L);
        when(profileService.getCurrentBeneficiaryCount(anyString(), anyString(), anyLong())).thenReturn(3L);
        when(profileService.getCurrentBeneficiaryAmount(anyString(), anyString(), anyLong())).thenReturn(150000L);
        when(profileService.getCurrentDailyBeneficiaryAmount(anyString(), anyString(), anyLong())).thenReturn(50000L);
        when(ruleEngine.evaluateAll(any(), any(), any())).thenReturn(ruleResults);
        when(riskScoringService.computeResult(any(), any())).thenReturn(evalResult);

        EvaluationResult result = evaluationService.evaluate(txn);

        assertThat(result.getAction()).isEqualTo("ALERT");
        verify(ruleEngine).evaluateAll(any(), eq(profile), any());
        verify(riskScoringService).computeResult(eq(txn), eq(ruleResults));
        verify(profileService).updateProfile(eq(profile), eq(txn));
        verify(riskResultRepository).save(eq(evalResult));
    }

    @Test
    void evaluate_alertResult_enqueuesToReviewQueue() {
        Transaction txn = TestDataFactory.createTransaction("TXN-1", "C-1", "NEFT", 50000);
        ClientProfile profile = TestDataFactory.createClientProfile("C-1", 100);
        EvaluationResult alertResult = TestDataFactory.createEvaluationResult("TXN-1", "C-1", 45.0, "ALERT");

        when(profileService.getOrCreateProfile("C-1")).thenReturn(profile);
        when(profileService.getCurrentHourlyCount(anyString(), anyLong())).thenReturn(0L);
        when(profileService.getCurrentHourlyAmount(anyString(), anyLong())).thenReturn(0L);
        when(profileService.getCurrentDailyCount(anyString(), anyLong())).thenReturn(0L);
        when(profileService.getCurrentDailyAmount(anyString(), anyLong())).thenReturn(0L);
        when(profileService.getCurrentDailyNewBeneCount(anyString(), anyLong())).thenReturn(0L);
        when(profileService.getCurrentBeneficiaryCount(anyString(), anyString(), anyLong())).thenReturn(0L);
        when(profileService.getCurrentBeneficiaryAmount(anyString(), anyString(), anyLong())).thenReturn(0L);
        when(profileService.getCurrentDailyBeneficiaryAmount(anyString(), anyString(), anyLong())).thenReturn(0L);
        when(ruleEngine.evaluateAll(any(), any(), any())).thenReturn(alertResult.getRuleResults());
        when(riskScoringService.computeResult(any(), any())).thenReturn(alertResult);

        evaluationService.evaluate(txn);

        ArgumentCaptor<ReviewQueueItem> captor = ArgumentCaptor.forClass(ReviewQueueItem.class);
        verify(reviewQueueRepository).save(captor.capture());
        ReviewQueueItem enqueued = captor.getValue();
        assertThat(enqueued.getTxnId()).isEqualTo("TXN-1");
        assertThat(enqueued.getAction()).isEqualTo("ALERT");
        assertThat(enqueued.getFeedbackStatus()).isEqualTo(ReviewStatus.PENDING);
    }

    @Test
    void evaluate_passResult_doesNotEnqueue() {
        Transaction txn = TestDataFactory.createTransaction("TXN-1", "C-1", "NEFT", 50000);
        ClientProfile profile = TestDataFactory.createClientProfile("C-1", 100);
        EvaluationResult passResult = TestDataFactory.createEvaluationResult("TXN-1", "C-1", 10.0, "PASS");

        when(profileService.getOrCreateProfile("C-1")).thenReturn(profile);
        when(profileService.getCurrentHourlyCount(anyString(), anyLong())).thenReturn(0L);
        when(profileService.getCurrentHourlyAmount(anyString(), anyLong())).thenReturn(0L);
        when(profileService.getCurrentDailyCount(anyString(), anyLong())).thenReturn(0L);
        when(profileService.getCurrentDailyAmount(anyString(), anyLong())).thenReturn(0L);
        when(profileService.getCurrentDailyNewBeneCount(anyString(), anyLong())).thenReturn(0L);
        when(profileService.getCurrentBeneficiaryCount(anyString(), anyString(), anyLong())).thenReturn(0L);
        when(profileService.getCurrentBeneficiaryAmount(anyString(), anyString(), anyLong())).thenReturn(0L);
        when(profileService.getCurrentDailyBeneficiaryAmount(anyString(), anyString(), anyLong())).thenReturn(0L);
        when(ruleEngine.evaluateAll(any(), any(), any())).thenReturn(Collections.emptyList());
        when(riskScoringService.computeResult(any(), any())).thenReturn(passResult);

        evaluationService.evaluate(txn);

        verify(reviewQueueRepository, never()).save(any(ReviewQueueItem.class));
    }
}
