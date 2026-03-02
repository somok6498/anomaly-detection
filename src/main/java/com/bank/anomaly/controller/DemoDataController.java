package com.bank.anomaly.controller;

import com.bank.anomaly.model.EvaluationResult;
import com.bank.anomaly.model.PagedResponse;
import com.bank.anomaly.model.ReviewQueueItem;
import com.bank.anomaly.model.ReviewStatus;
import com.bank.anomaly.model.Transaction;
import com.bank.anomaly.service.ReviewQueueService;
import com.bank.anomaly.service.TransactionEvaluationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

@RestController
@RequestMapping("/api/v1/demo")
@Tag(name = "Demo Data", description = "Generate realistic demo data for showcase purposes")
public class DemoDataController {

    private static final Logger log = LoggerFactory.getLogger(DemoDataController.class);

    private final TransactionEvaluationService evaluationService;
    private final ReviewQueueService reviewQueueService;

    private static final String[] TYPES = {"NEFT", "RTGS", "IMPS", "UPI"};
    private static final String[] IFSC_PREFIXES = {
            "HDFC", "ICIC", "SBIN", "UTIB", "KKBK", "CNRB", "BARB", "PUNB", "AXIS"
    };

    public DemoDataController(TransactionEvaluationService evaluationService,
                              ReviewQueueService reviewQueueService) {
        this.evaluationService = evaluationService;
        this.reviewQueueService = reviewQueueService;
    }

    @PostMapping("/generate")
    @Operation(summary = "Generate demo data",
               description = "Creates realistic transactions across 10 scenarios to showcase all rules, review queue, and metrics")
    public ResponseEntity<Map<String, Object>> generateDemoData() {
        long startTime = System.currentTimeMillis();
        Random rng = ThreadLocalRandom.current();
        long now = System.currentTimeMillis();

        List<EvaluationResult> allResults = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // ── PHASE 1: Warm-up — build profiles past minProfileTxns=20 threshold ──
        log.info("Demo data: Phase 1 — Warm-up (building client profiles)");
        for (int clientNum = 1; clientNum <= 5; clientNum++) {
            String clientId = String.format("CLIENT-%03d", clientNum);
            for (int i = 0; i < 25; i++) {
                long ts = now - 7L * 86400000 + i * (7L * 86400000 / 25);
                String type = TYPES[i % TYPES.length];
                double amount = 1000 + rng.nextDouble() * 4000;
                String beneAcct = String.format("%010d", rng.nextInt(999999999));
                String beneIfsc = randomIfsc(rng);
                eval(allResults, errors,
                        String.format("DEMO-WARM-%s-%03d", clientId, i),
                        clientId, type, amount, ts, beneAcct, beneIfsc);
            }
        }

        // ── PHASE 2: Anomalous scenarios ──
        log.info("Demo data: Phase 2 — Anomalous scenarios");

        // Scenario 1: Large amounts → AMOUNT_ANOMALY
        eval(allResults, errors, "DEMO-BIGAMT-1", "CLIENT-001", "NEFT", 250000.00,
                now - 100000, "9999888877", "ICIC0001234");
        eval(allResults, errors, "DEMO-BIGAMT-2", "CLIENT-002", "RTGS", 500000.00,
                now - 200000, "8888777766", "SBIN0005678");
        eval(allResults, errors, "DEMO-BIGAMT-3", "CLIENT-003", "IMPS", 175000.50,
                now - 300000, "7777666655", "UTIB0009012");
        eval(allResults, errors, "DEMO-BIGAMT-4", "CLIENT-004", "UPI", 95000.00,
                now - 400000, "6666555544", "KKBK0003456");
        eval(allResults, errors, "DEMO-BIGAMT-5", "CLIENT-005", "NEFT", 450000.00,
                now - 500000, "5555444433", "CNRB0007890");

        // Scenario 2: Unusual txn types → TRANSACTION_TYPE_ANOMALY
        eval(allResults, errors, "DEMO-ODDTYPE-1", "CLIENT-001", "RTGS", 15000,
                now - 600000, "1234567890", "BARB0001111");
        eval(allResults, errors, "DEMO-ODDTYPE-2", "CLIENT-001", "RTGS", 22000,
                now - 700000, "1234567891", "BARB0001112");
        eval(allResults, errors, "DEMO-ODDTYPE-3", "CLIENT-003", "IFT", 80000,
                now - 800000, "9012345678", "BARB0002222");

        // Scenario 3: Rapid-fire same beneficiary → BENE_RAPID_REPEAT
        for (int i = 1; i <= 6; i++) {
            eval(allResults, errors, "DEMO-RAPID-" + i, "CLIENT-002", "IMPS",
                    3000 + i * 500.0, now - (6 - i) * 60000L,
                    "4444333322", "HDFC0009999");
        }

        // Scenario 4: New beneficiary velocity → NEW_BENEFICIARY_VELOCITY
        for (int i = 1; i <= 6; i++) {
            eval(allResults, errors, "DEMO-NEWBENE-" + i, "CLIENT-004", "NEFT",
                    2000 + rng.nextInt(3000), now - (6 - i) * 120000L,
                    String.format("NEW%07d", rng.nextInt(9999999)), randomIfsc(rng));
        }

        // Scenario 5: High daily cumulative → DAILY_CUMULATIVE_AMOUNT
        for (int i = 1; i <= 6; i++) {
            eval(allResults, errors, "DEMO-CUMUL-" + i, "CLIENT-005", "UPI",
                    8000 + rng.nextInt(5000), now - (6 - i) * 180000L,
                    String.format("CUM%07d", i * 1111),
                    "SBIN000" + String.format("%04d", i * 111));
        }

        // Scenario 6: Dormant reactivation → DORMANCY_REACTIVATION
        eval(allResults, errors, "DEMO-DORMANT-1", "CLIENT-008", "NEFT", 35000,
                now, "8080808080", "PUNB0001234");
        eval(allResults, errors, "DEMO-DORMANT-2", "CLIENT-009", "IMPS", 42000,
                now, "9090909090", "CNRB0005678");
        eval(allResults, errors, "DEMO-DORMANT-3", "CLIENT-010", "UPI", 28000,
                now, "1010101010", "BARB0009012");

        // Scenario 7: Moderate risk mix → multiple rules
        eval(allResults, errors, "DEMO-MODERATE-1", "CLIENT-001", "NEFT", 25000,
                now - 1000000, "3333222211", "HDFC0004567");
        eval(allResults, errors, "DEMO-MODERATE-2", "CLIENT-003", "IMPS", 30000,
                now - 1100000, "2222111100", "ICIC0007890");
        eval(allResults, errors, "DEMO-MODERATE-3", "CLIENT-006", "RTGS", 55000,
                now - 1200000, "1111000099", "SBIN0001234");
        eval(allResults, errors, "DEMO-MODERATE-4", "CLIENT-007", "NEFT", 45000,
                now - 1300000, "0000999988", "UTIB0005678");

        // Scenario 8: Same-amount structuring → BENE_AMOUNT_REPETITION
        for (int i = 1; i <= 4; i++) {
            eval(allResults, errors, "DEMO-SAMEAMT-" + i, "CLIENT-003", "NEFT",
                    49999.00, now - (4 - i) * 300000L,
                    "7777777777", "BARB0003333");
        }

        // Scenario 9: Cross-channel same beneficiary → CROSS_CHANNEL_BENEFICIARY_AMOUNT
        String[] crossTypes = {"NEFT", "IMPS", "UPI", "RTGS"};
        for (int i = 0; i < 4; i++) {
            eval(allResults, errors, "DEMO-XCHAN-" + (i + 1), "CLIENT-001",
                    crossTypes[i], 20000, now - (4 - i) * 10000L,
                    "5555666677", "HDFC0008888");
        }

        // ── PHASE 3: Submit feedback on review queue items ──
        log.info("Demo data: Phase 3 — Submitting feedback");
        int feedbackSubmitted = submitFeedbackOnPending(rng, errors);

        // ── Build response ──
        int passCount = 0, alertCount = 0, blockCount = 0;
        for (EvaluationResult r : allResults) {
            switch (r.getAction()) {
                case "PASS" -> passCount++;
                case "ALERT" -> alertCount++;
                case "BLOCK" -> blockCount++;
            }
        }

        long durationMs = System.currentTimeMillis() - startTime;
        log.info("Demo data generation complete: {} evaluated, {} feedback, {}ms",
                allResults.size(), feedbackSubmitted, durationMs);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalEvaluated", allResults.size());
        response.put("results", Map.of("PASS", passCount, "ALERT", alertCount, "BLOCK", blockCount));
        response.put("feedbackSubmitted", feedbackSubmitted);
        response.put("durationMs", durationMs);
        if (!errors.isEmpty()) {
            response.put("errors", errors.size() > 10 ? errors.subList(0, 10) : errors);
        }
        return ResponseEntity.ok(response);
    }

    private void eval(List<EvaluationResult> results, List<String> errors,
                      String txnId, String clientId, String txnType,
                      double amount, long timestamp, String beneAcct, String beneIfsc) {
        try {
            EvaluationResult result = evaluationService.evaluate(Transaction.builder()
                    .txnId(txnId)
                    .clientId(clientId)
                    .txnType(txnType)
                    .amount(amount)
                    .timestamp(timestamp)
                    .beneficiaryAccount(beneAcct)
                    .beneficiaryIfsc(beneIfsc)
                    .build());
            results.add(result);
        } catch (Exception e) {
            errors.add(txnId + ": " + e.getMessage());
        }
    }

    private int submitFeedbackOnPending(Random rng, List<String> errors) {
        int count = 0;
        try {
            PagedResponse<ReviewQueueItem> queue = reviewQueueService.getQueueItems(
                    null, null, null, null, null, "PENDING", 200, null);
            List<ReviewQueueItem> pending = queue.data().stream().toList();

            int toFeedback = Math.max(1, (int) (pending.size() * 0.6));
            for (int i = 0; i < Math.min(toFeedback, pending.size()); i++) {
                ReviewStatus status = rng.nextDouble() < 0.6
                        ? ReviewStatus.TRUE_POSITIVE
                        : ReviewStatus.FALSE_POSITIVE;
                try {
                    reviewQueueService.submitFeedback(
                            pending.get(i).getTxnId(), status, "demo-generator");
                    count++;
                } catch (Exception e) {
                    errors.add("feedback " + pending.get(i).getTxnId() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            errors.add("feedback phase: " + e.getMessage());
        }
        return count;
    }

    private String randomIfsc(Random rng) {
        return IFSC_PREFIXES[rng.nextInt(IFSC_PREFIXES.length)]
                + String.format("000%04d", rng.nextInt(9999));
    }
}
