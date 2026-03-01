package com.bank.anomaly.controller;

import com.bank.anomaly.config.RiskThresholdConfig;
import com.bank.anomaly.model.EvaluationResult;
import com.bank.anomaly.model.PagedResponse;
import com.bank.anomaly.model.Transaction;
import com.bank.anomaly.repository.RiskResultRepository;
import com.bank.anomaly.service.TransactionEvaluationService;
import com.bank.anomaly.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/transactions")
@Tag(name = "Transactions", description = "Submit transactions for anomaly evaluation and query transaction history")
public class TransactionController {

    private final TransactionEvaluationService evaluationService;
    private final RiskResultRepository riskResultRepository;
    private final TransactionService transactionService;
    private final RiskThresholdConfig thresholdConfig;

    public TransactionController(TransactionEvaluationService evaluationService,
                                 RiskResultRepository riskResultRepository,
                                 TransactionService transactionService,
                                 RiskThresholdConfig thresholdConfig) {
        this.evaluationService = evaluationService;
        this.riskResultRepository = riskResultRepository;
        this.transactionService = transactionService;
        this.thresholdConfig = thresholdConfig;
    }

    @Operation(summary = "Evaluate a transaction for anomalies",
            description = "Submits a transaction for real-time anomaly evaluation against all active rules " +
                    "(including the Isolation Forest ML model). Returns composite risk score, risk level, " +
                    "action (PASS/ALERT/BLOCK), and per-rule breakdown.")
    @PostMapping("/evaluate")
    public ResponseEntity<?> evaluateTransaction(@RequestBody Transaction txn) {
        if (txn.getTxnId() == null || txn.getClientId() == null || txn.getTxnType() == null) {
            return ResponseEntity.badRequest().build();
        }

        if (!thresholdConfig.getTransactionTypes().contains(txn.getTxnType())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid transaction type: " + txn.getTxnType(),
                    "validTypes", thresholdConfig.getTransactionTypes()
            ));
        }

        if (txn.getTimestamp() == 0) {
            txn.setTimestamp(System.currentTimeMillis());
        }

        EvaluationResult result = evaluationService.evaluate(txn);
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Get a transaction by ID",
            description = "Retrieves a persisted transaction by its unique transaction ID.")
    @GetMapping("/{txnId}")
    public ResponseEntity<Transaction> getTransaction(
            @Parameter(description = "Transaction ID", example = "CLIENT-001-TXN-000001")
            @PathVariable String txnId) {
        Transaction txn = transactionService.getTransaction(txnId);
        if (txn == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(txn);
    }

    @Operation(summary = "List transactions by client ID",
            description = "Retrieves recent transactions for a specific client, ordered by timestamp. Supports cursor-based pagination.")
    @GetMapping("/client/{clientId}")
    public ResponseEntity<PagedResponse<Transaction>> getTransactionsByClient(
            @Parameter(description = "Client ID", example = "CLIENT-001")
            @PathVariable String clientId,
            @Parameter(description = "Max number of transactions to return", example = "50")
            @RequestParam(defaultValue = "50") int limit,
            @Parameter(description = "Cursor: return records with timestamp before this value")
            @RequestParam(required = false) Long before) {
        PagedResponse<Transaction> response = transactionService.getTransactionsByClientId(clientId, limit, before);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Get evaluation result for a transaction",
            description = "Retrieves the anomaly evaluation result for a previously evaluated transaction.")
    @GetMapping("/results/{txnId}")
    public ResponseEntity<EvaluationResult> getResult(
            @Parameter(description = "Transaction ID", example = "IF-DEMO-001")
            @PathVariable String txnId) {
        EvaluationResult result = riskResultRepository.findByTxnId(txnId);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "List evaluation results by client ID",
            description = "Retrieves recent evaluation results for a specific client. Supports cursor-based pagination.")
    @GetMapping("/results/client/{clientId}")
    public ResponseEntity<PagedResponse<EvaluationResult>> getResultsByClient(
            @Parameter(description = "Client ID", example = "CLIENT-001")
            @PathVariable String clientId,
            @Parameter(description = "Max number of results to return", example = "20")
            @RequestParam(defaultValue = "20") int limit,
            @Parameter(description = "Cursor: return records with evaluatedAt before this value")
            @RequestParam(required = false) Long before) {
        PagedResponse<EvaluationResult> results = riskResultRepository.findByClientId(clientId, limit, before);
        return ResponseEntity.ok(results);
    }
}
