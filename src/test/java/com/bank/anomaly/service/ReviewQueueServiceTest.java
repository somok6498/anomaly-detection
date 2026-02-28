package com.bank.anomaly.service;

import com.bank.anomaly.config.MetricsConfig;
import com.bank.anomaly.model.*;
import com.bank.anomaly.repository.ClientProfileRepository;
import com.bank.anomaly.repository.ReviewQueueRepository;
import com.bank.anomaly.repository.RiskResultRepository;
import com.bank.anomaly.repository.TransactionRepository;
import com.bank.anomaly.testutil.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewQueueServiceTest {

    @Mock private ReviewQueueRepository reviewQueueRepo;
    @Mock private RiskResultRepository riskResultRepo;
    @Mock private TransactionRepository transactionRepo;
    @Mock private ClientProfileRepository profileRepo;
    @Mock private MetricsConfig metricsConfig;

    private ReviewQueueService service;

    @BeforeEach
    void setUp() {
        service = new ReviewQueueService(reviewQueueRepo, riskResultRepo, transactionRepo, profileRepo, metricsConfig);
    }

    @Test
    void getQueueItems_delegatesToRepository() {
        PagedResponse<ReviewQueueItem> expected = new PagedResponse<>(List.of(
                TestDataFactory.createReviewQueueItem("T1", "C-1", ReviewStatus.PENDING)),
                false, null);
        when(reviewQueueRepo.findByFilters("ALERT", "C-1", null, null, null, 100, null))
                .thenReturn(expected);

        PagedResponse<ReviewQueueItem> result = service.getQueueItems("ALERT", "C-1", null, null, null, 100, null);
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void getQueueItemDetail_found_assemblesFullDetail() {
        ReviewQueueItem item = TestDataFactory.createReviewQueueItem("T1", "C-1", ReviewStatus.PENDING);
        EvaluationResult eval = TestDataFactory.createEvaluationResult("T1", "C-1", 55.0, "ALERT");
        Transaction txn = TestDataFactory.createTransaction("T1", "C-1", "NEFT", 50000);
        ClientProfile profile = TestDataFactory.createClientProfile("C-1", 5000);

        when(reviewQueueRepo.findByTxnId("T1")).thenReturn(item);
        when(riskResultRepo.findByTxnId("T1")).thenReturn(eval);
        when(transactionRepo.findByTxnId("T1")).thenReturn(txn);
        when(profileRepo.findByClientId("C-1")).thenReturn(profile);

        ReviewQueueDetail detail = service.getQueueItemDetail("T1");

        assertThat(detail).isNotNull();
        assertThat(detail.getQueueItem().getTxnId()).isEqualTo("T1");
        assertThat(detail.getEvaluation().getCompositeScore()).isEqualTo(55.0);
        assertThat(detail.getTransaction().getAmount()).isEqualTo(50000.0);
    }

    @Test
    void getQueueItemDetail_notFound_returnsNull() {
        when(reviewQueueRepo.findByTxnId("MISSING")).thenReturn(null);
        assertThat(service.getQueueItemDetail("MISSING")).isNull();
    }

    @Test
    void submitFeedback_invalidStatus_throwsException() {
        assertThatThrownBy(() -> service.submitFeedback("T1", ReviewStatus.PENDING, "ops"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TRUE_POSITIVE or FALSE_POSITIVE");
    }

    @Test
    void submitFeedback_validStatus_updatesAndReturns() {
        ReviewQueueItem updated = TestDataFactory.createReviewQueueItem("T1", "C-1", ReviewStatus.TRUE_POSITIVE);
        when(reviewQueueRepo.updateFeedback("T1", ReviewStatus.TRUE_POSITIVE, "ops")).thenReturn(true);
        when(reviewQueueRepo.findByTxnId("T1")).thenReturn(updated);

        ReviewQueueItem result = service.submitFeedback("T1", ReviewStatus.TRUE_POSITIVE, "ops");

        assertThat(result.getFeedbackStatus()).isEqualTo(ReviewStatus.TRUE_POSITIVE);
        verify(metricsConfig).recordFeedback("TRUE_POSITIVE");
    }

    @Test
    void getQueueStats_mapsArrayToMap() {
        when(reviewQueueRepo.countByStatus()).thenReturn(new int[]{10, 5, 3, 2});

        Map<String, Integer> stats = service.getQueueStats();

        assertThat(stats).containsEntry("pending", 10)
                .containsEntry("truePositive", 5)
                .containsEntry("falsePositive", 3)
                .containsEntry("autoAccepted", 2);
    }
}
