package com.bank.anomaly.service;

import com.bank.anomaly.model.Transaction;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Random;
import java.util.concurrent.*;

/**
 * TEMPORARY — generates 30 minutes of realistic transaction traffic for
 * 10 clients across all txn types and rule categories to populate Grafana panels.
 *
 * Client roles:
 *   CLIENT-001: Normal baseline (mostly PASS)
 *   CLIENT-002: Amount anomaly (spikes, hourly cumulative)
 *   CLIENT-003: TPS spiker (velocity bursts)
 *   CLIENT-004: Beneficiary concentrator (same bene, rapid repeat)
 *   CLIENT-005: Txn type anomaly (switches to rare types)
 *   CLIENT-006: Beneficiary amount repeater (same amount + cross-channel)
 *   CLIENT-007: New beneficiary velocity (fan-out to many new benes)
 *   CLIENT-008: Dormancy + reactivation (silent then burst)
 *   CLIENT-009: Low-and-slow drip (many small txns)
 *   CLIENT-010: Mixed high-risk (multiple rules, BLOCK-level)
 *
 * DELETE THIS CLASS after demo data generation.
 */
@Component
public class DemoTrafficScheduler {

    private static final Logger log = LoggerFactory.getLogger(DemoTrafficScheduler.class);

    private final TransactionEvaluationService evaluationService;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Random rng = ThreadLocalRandom.current();
    private int txnSeq = 0;

    private static final String[] CLIENTS = {
        "CLIENT-001", "CLIENT-002", "CLIENT-003", "CLIENT-004", "CLIENT-005",
        "CLIENT-006", "CLIENT-007", "CLIENT-008", "CLIENT-009", "CLIENT-010"
    };
    private static final String[] TYPES = {"NEFT", "RTGS", "IMPS", "UPI", "IFT"};
    private static final String[] IFSC = {
        "HDFC0001234", "ICIC0005678", "SBIN0009012", "UTIB0003456", "BARB0007890"
    };

    // Fixed beneficiaries for concentration/repeat patterns
    private static final String FIXED_BENE_1 = "9999900001";
    private static final String FIXED_BENE_2 = "9999900002";
    private static final String FIXED_IFSC = "HDFC0001234";

    public DemoTrafficScheduler(TransactionEvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @PostConstruct
    public void start() {
        log.info("DemoTrafficScheduler: Starting 30-minute traffic generation for 10 clients");
        long startMs = System.currentTimeMillis();
        long endMs = startMs + 30 * 60 * 1000L;

        scheduler.scheduleAtFixedRate(() -> {
            try {
                long elapsed = System.currentTimeMillis() - startMs;
                long elapsedMin = elapsed / 60_000;

                if (System.currentTimeMillis() >= endMs) {
                    log.info("DemoTrafficScheduler: 30 minutes complete. Shutting down.");
                    scheduler.shutdown();
                    return;
                }

                generateForPhase(elapsedMin);
            } catch (Exception e) {
                log.warn("DemoTrafficScheduler tick error: {}", e.getMessage());
            }
        }, 5, 3, TimeUnit.SECONDS);
    }

    private void generateForPhase(long elapsedMin) {
        if (elapsedMin < 3) {
            phase1Baseline();
        } else if (elapsedMin < 6) {
            phase2AmountAndTpsSpikes();
        } else if (elapsedMin < 9) {
            phase3BeneficiaryAndTypeAnomalies();
        } else if (elapsedMin < 12) {
            phase4NewBeneAndDormancy();
        } else if (elapsedMin < 15) {
            phase5DripAndMixedRisk();
        } else if (elapsedMin < 18) {
            phase6ReactivationAndCrossChannel();
        } else if (elapsedMin < 21) {
            phase7VolumeRamp();
        } else if (elapsedMin < 24) {
            phase8Escalation();
        } else if (elapsedMin < 27) {
            phase9MixedActivity();
        } else {
            phase10WindDown();
        }
    }

    // Phase 1: Baseline — all clients normal to build profiles
    private void phase1Baseline() {
        for (String client : CLIENTS) {
            sendNormal(client);
            if (rng.nextBoolean()) sendNormal(client);
        }
    }

    // Phase 2: CLIENT-002 amount spikes, CLIENT-003 TPS burst
    private void phase2AmountAndTpsSpikes() {
        // CLIENT-001: normal
        sendNormal("CLIENT-001");

        // CLIENT-002: amount anomaly — large amounts
        sendTxn("CLIENT-002", randomType(), spikeAmount());
        sendTxn("CLIENT-002", randomType(), spikeAmount());

        // CLIENT-003: TPS spike — many txns per tick
        for (int i = 0; i < 6; i++) {
            sendTxn("CLIENT-003", randomType(), normalAmount());
        }

        // CLIENT-004 through CLIENT-010: normal baseline
        for (int i = 3; i < CLIENTS.length; i++) {
            sendNormal(CLIENTS[i]);
        }
    }

    // Phase 3: Beneficiary concentration, type anomaly, amount repetition
    private void phase3BeneficiaryAndTypeAnomalies() {
        sendNormal("CLIENT-001");
        sendTxn("CLIENT-002", randomType(), normalAmount());
        sendNormal("CLIENT-003");

        // CLIENT-004: beneficiary concentration — same bene repeatedly
        sendTxnWithBene("CLIENT-004", "NEFT", normalAmount(), FIXED_BENE_1, FIXED_IFSC);
        sendTxnWithBene("CLIENT-004", "NEFT", normalAmount(), FIXED_BENE_1, FIXED_IFSC);
        if (rng.nextBoolean()) sendTxnWithBene("CLIENT-004", "NEFT", normalAmount(), FIXED_BENE_1, FIXED_IFSC);

        // CLIENT-005: txn type anomaly — switches to only IFT (rare)
        sendTxn("CLIENT-005", "IFT", normalAmount());
        sendTxn("CLIENT-005", "IFT", normalAmount());

        // CLIENT-006: same amount to same beneficiary (amount repetition)
        double fixedAmount = 25000.0;
        sendTxnWithBene("CLIENT-006", "NEFT", fixedAmount, FIXED_BENE_2, FIXED_IFSC);
        sendTxnWithBene("CLIENT-006", "NEFT", fixedAmount, FIXED_BENE_2, FIXED_IFSC);

        sendNormal("CLIENT-007");
        sendNormal("CLIENT-008");
        sendNormal("CLIENT-009");
        sendNormal("CLIENT-010");
    }

    // Phase 4: New beneficiary velocity, dormancy
    private void phase4NewBeneAndDormancy() {
        sendNormal("CLIENT-001");
        sendTxn("CLIENT-002", randomType(), spikeAmount()); // continued amount stress
        sendNormal("CLIENT-003");

        // CLIENT-004: continued concentration
        sendTxnWithBene("CLIENT-004", "RTGS", normalAmount(), FIXED_BENE_1, FIXED_IFSC);

        sendNormal("CLIENT-005");
        sendNormal("CLIENT-006");

        // CLIENT-007: new beneficiary velocity — fan out to unique benes every tick
        for (int i = 0; i < 4; i++) {
            String uniqueBene = String.format("NEW-%010d", txnSeq + i + rng.nextInt(99999));
            sendTxnWithBene("CLIENT-007", randomType(), normalAmount(), uniqueBene, randomIfsc());
        }

        // CLIENT-008: SILENT — no transactions (dormancy detection)

        sendNormal("CLIENT-009");
        sendNormal("CLIENT-010");
    }

    // Phase 5: Drip feeding, mixed high-risk
    private void phase5DripAndMixedRisk() {
        sendNormal("CLIENT-001");
        sendTxn("CLIENT-002", randomType(), normalAmount());
        sendNormal("CLIENT-003");
        sendTxnWithBene("CLIENT-004", "NEFT", normalAmount(), FIXED_BENE_1, FIXED_IFSC);
        sendTxn("CLIENT-005", "IFT", normalAmount()); // continued rare type
        sendNormal("CLIENT-006");
        sendNormal("CLIENT-007");

        // CLIENT-008: still silent

        // CLIENT-009: low-and-slow drip — many small txns
        for (int i = 0; i < 5; i++) {
            sendTxn("CLIENT-009", randomType(), dripAmount());
        }

        // CLIENT-010: mixed high-risk — high amounts + rapid beneficiary repeat
        sendTxnWithBene("CLIENT-010", "RTGS", largeTxnAmount(), FIXED_BENE_1, FIXED_IFSC);
        sendTxnWithBene("CLIENT-010", "RTGS", largeTxnAmount(), FIXED_BENE_1, FIXED_IFSC);
        sendTxn("CLIENT-010", "IFT", spikeAmount()); // unusual type + high amount
    }

    // Phase 6: Reactivation, cross-channel, another TPS spike
    private void phase6ReactivationAndCrossChannel() {
        sendNormal("CLIENT-001");
        sendTxn("CLIENT-002", randomType(), spikeAmount());

        // CLIENT-003: another TPS burst
        for (int i = 0; i < 7; i++) {
            sendTxn("CLIENT-003", randomType(), normalAmount());
        }

        sendTxnWithBene("CLIENT-004", "IMPS", normalAmount(), FIXED_BENE_1, FIXED_IFSC);
        sendTxn("CLIENT-005", "IFT", spikeAmount()); // rare type + high amount

        // CLIENT-006: cross-channel — same bene + same amount via different types
        double crossAmt = 30000.0;
        sendTxnWithBene("CLIENT-006", "NEFT", crossAmt, FIXED_BENE_2, FIXED_IFSC);
        sendTxnWithBene("CLIENT-006", "RTGS", crossAmt, FIXED_BENE_2, FIXED_IFSC);
        sendTxnWithBene("CLIENT-006", "IMPS", crossAmt, FIXED_BENE_2, FIXED_IFSC);

        sendNormal("CLIENT-007");

        // CLIENT-008: reactivation burst after silence
        sendTxn("CLIENT-008", "UPI", spikeAmount());
        sendTxn("CLIENT-008", "UPI", spikeAmount());
        sendTxn("CLIENT-008", "NEFT", spikeAmount());

        sendNormal("CLIENT-009");
        sendNormal("CLIENT-010");
    }

    // Phase 7: Volume ramp — most clients active, continued anomalies
    private void phase7VolumeRamp() {
        // All clients active with higher volume
        sendNormal("CLIENT-001");
        if (rng.nextBoolean()) sendNormal("CLIENT-001");

        // CLIENT-002: hourly amount spike
        sendTxn("CLIENT-002", "RTGS", largeTxnAmount());
        sendTxn("CLIENT-002", "NEFT", spikeAmount());

        // CLIENT-003: sustained high TPS
        for (int i = 0; i < 5; i++) {
            sendTxn("CLIENT-003", randomType(), normalAmount());
        }

        // CLIENT-004: continued concentration
        sendTxnWithBene("CLIENT-004", randomType(), normalAmount(), FIXED_BENE_1, FIXED_IFSC);
        sendTxnWithBene("CLIENT-004", randomType(), normalAmount(), FIXED_BENE_1, FIXED_IFSC);

        sendTxn("CLIENT-005", rng.nextInt(3) == 0 ? "IFT" : randomType(), normalAmount());
        sendNormal("CLIENT-006");

        // CLIENT-007: continued new bene fan-out
        for (int i = 0; i < 3; i++) {
            String uniqueBene = String.format("FAN-%010d", txnSeq + rng.nextInt(99999));
            sendTxnWithBene("CLIENT-007", randomType(), normalAmount(), uniqueBene, randomIfsc());
        }

        sendNormal("CLIENT-008");

        // CLIENT-009: continued drip
        for (int i = 0; i < 4; i++) {
            sendTxn("CLIENT-009", randomType(), dripAmount());
        }

        sendTxn("CLIENT-010", randomType(), spikeAmount());
    }

    // Phase 8: CLIENT-010 escalation — multiple rules simultaneously
    private void phase8Escalation() {
        sendNormal("CLIENT-001");
        sendTxn("CLIENT-002", randomType(), spikeAmount());
        sendNormal("CLIENT-003");
        sendTxnWithBene("CLIENT-004", "NEFT", normalAmount(), FIXED_BENE_1, FIXED_IFSC);
        sendTxn("CLIENT-005", "IFT", normalAmount());
        sendNormal("CLIENT-006");
        sendNormal("CLIENT-007");
        sendNormal("CLIENT-008");
        sendNormal("CLIENT-009");

        // CLIENT-010: full escalation — high amount + rapid repeat + unusual type + many benes
        sendTxnWithBene("CLIENT-010", "IFT", largeTxnAmount(), FIXED_BENE_1, FIXED_IFSC);
        sendTxnWithBene("CLIENT-010", "IFT", largeTxnAmount(), FIXED_BENE_1, FIXED_IFSC);
        sendTxnWithBene("CLIENT-010", "IFT", largeTxnAmount(), FIXED_BENE_2, FIXED_IFSC);
        sendTxn("CLIENT-010", "IFT", spikeAmount());
        // Also fan out to new benes
        String newBene = String.format("ESC-%010d", txnSeq + rng.nextInt(99999));
        sendTxnWithBene("CLIENT-010", "RTGS", largeTxnAmount(), newBene, randomIfsc());
    }

    // Phase 9: Mixed — some normalize, some maintain anomalous
    private void phase9MixedActivity() {
        sendNormal("CLIENT-001");
        sendTxn("CLIENT-002", randomType(), normalAmount()); // calming down

        // CLIENT-003: one more spike
        for (int i = 0; i < 4; i++) {
            sendTxn("CLIENT-003", randomType(), normalAmount());
        }

        sendTxnWithBene("CLIENT-004", "UPI", normalAmount(), FIXED_BENE_1, FIXED_IFSC);
        sendTxn("CLIENT-005", randomType(), normalAmount()); // back to normal types

        // CLIENT-006: one more cross-channel round
        double crossAmt = 15000.0;
        sendTxnWithBene("CLIENT-006", "UPI", crossAmt, FIXED_BENE_2, FIXED_IFSC);
        sendTxnWithBene("CLIENT-006", "IMPS", crossAmt, FIXED_BENE_2, FIXED_IFSC);

        sendNormal("CLIENT-007");
        sendNormal("CLIENT-008");

        // CLIENT-009: still dripping
        for (int i = 0; i < 3; i++) {
            sendTxn("CLIENT-009", randomType(), dripAmount());
        }

        sendTxn("CLIENT-010", randomType(), spikeAmount());
    }

    // Phase 10: Wind-down — all clients low/normal
    private void phase10WindDown() {
        for (String client : CLIENTS) {
            if (rng.nextInt(3) != 0) {
                sendNormal(client);
            }
        }
    }

    // --- Helper methods ---

    private void sendNormal(String clientId) {
        sendTxn(clientId, randomType(), normalAmount());
    }

    private void sendTxn(String clientId, String txnType, double amount) {
        sendTxnWithBene(clientId, txnType, amount, randomBene(), randomIfsc());
    }

    private void sendTxnWithBene(String clientId, String txnType, double amount,
                                  String beneAcct, String beneIfsc) {
        txnSeq++;
        String txnId = String.format("DEMO-%s-%06d", clientId, txnSeq);
        try {
            evaluationService.evaluate(Transaction.builder()
                    .txnId(txnId)
                    .clientId(clientId)
                    .txnType(txnType)
                    .amount(amount)
                    .timestamp(System.currentTimeMillis())
                    .beneficiaryAccount(beneAcct)
                    .beneficiaryIfsc(beneIfsc)
                    .build());
        } catch (Exception e) {
            log.debug("DemoTrafficScheduler txn failed {}: {}", txnId, e.getMessage());
        }
    }

    private String randomType() {
        return TYPES[rng.nextInt(TYPES.length)];
    }

    private String randomBene() {
        return String.format("%010d", rng.nextInt(999999999));
    }

    private String randomIfsc() {
        return IFSC[rng.nextInt(IFSC.length)];
    }

    private double normalAmount() {
        return 1000 + rng.nextDouble() * 9000; // 1K–10K
    }

    private double spikeAmount() {
        return 50000 + rng.nextDouble() * 150000; // 50K–200K
    }

    private double largeTxnAmount() {
        return 200000 + rng.nextDouble() * 300000; // 200K–500K
    }

    private double dripAmount() {
        return 100 + rng.nextDouble() * 400; // 100–500 (low-and-slow)
    }
}
