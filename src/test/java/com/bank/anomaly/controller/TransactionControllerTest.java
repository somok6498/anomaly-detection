package com.bank.anomaly.controller;

import com.bank.anomaly.config.RiskThresholdConfig;
import com.bank.anomaly.model.EvaluationResult;
import com.bank.anomaly.model.PagedResponse;
import com.bank.anomaly.model.Transaction;
import com.bank.anomaly.repository.RiskResultRepository;
import com.bank.anomaly.service.TransactionEvaluationService;
import com.bank.anomaly.service.TransactionService;
import com.bank.anomaly.testutil.TestDataFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TransactionController.class)
class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TransactionEvaluationService evaluationService;

    @MockBean
    private RiskResultRepository riskResultRepository;

    @MockBean
    private TransactionService transactionService;

    @MockBean
    private RiskThresholdConfig thresholdConfig;

    @BeforeEach
    void setUp() {
        when(thresholdConfig.getTransactionTypes())
                .thenReturn(List.of("NEFT", "RTGS", "IMPS", "UPI", "IFT"));
    }

    @Test
    void evaluateTransaction_success() throws Exception {
        Transaction txn = TestDataFactory.createTransaction("TXN-1", "C-1", "NEFT", 50000);
        EvaluationResult result = TestDataFactory.createEvaluationResult("TXN-1", "C-1", 45.0, "ALERT");
        when(evaluationService.evaluate(any(Transaction.class))).thenReturn(result);

        mockMvc.perform(post("/api/v1/transactions/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(txn)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.txnId").value("TXN-1"))
                .andExpect(jsonPath("$.compositeScore").value(45.0))
                .andExpect(jsonPath("$.action").value("ALERT"))
                .andExpect(jsonPath("$.ruleResults").isArray());
    }

    @Test
    void evaluateTransaction_badRequest_missingFields() throws Exception {
        Transaction txn = Transaction.builder().amount(50000).build(); // no txnId, clientId, txnType

        mockMvc.perform(post("/api/v1/transactions/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(txn)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getTransaction_found() throws Exception {
        Transaction txn = TestDataFactory.createTransaction("TXN-1", "C-1", "NEFT", 50000);
        when(transactionService.getTransaction("TXN-1")).thenReturn(txn);

        mockMvc.perform(get("/api/v1/transactions/TXN-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.txnId").value("TXN-1"))
                .andExpect(jsonPath("$.clientId").value("C-1"));
    }

    @Test
    void getTransaction_notFound() throws Exception {
        when(transactionService.getTransaction("MISSING")).thenReturn(null);

        mockMvc.perform(get("/api/v1/transactions/MISSING"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getTransactionsByClient_success() throws Exception {
        PagedResponse<Transaction> pagedResponse = new PagedResponse<>(
                List.of(TestDataFactory.createTransaction("TXN-1", "C-1", "NEFT", 50000)),
                false, null);
        when(transactionService.getTransactionsByClientId(eq("C-1"), eq(50), isNull()))
                .thenReturn(pagedResponse);

        mockMvc.perform(get("/api/v1/transactions/client/C-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].txnId").value("TXN-1"))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void getResultsByClient_success() throws Exception {
        EvaluationResult result = TestDataFactory.createEvaluationResult("TXN-1", "C-1", 45.0, "ALERT");
        PagedResponse<EvaluationResult> pagedResponse = new PagedResponse<>(List.of(result), false, null);
        when(riskResultRepository.findByClientId(eq("C-1"), eq(20), isNull()))
                .thenReturn(pagedResponse);

        mockMvc.perform(get("/api/v1/transactions/results/client/C-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].compositeScore").value(45.0))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void getResult_found() throws Exception {
        EvaluationResult result = TestDataFactory.createEvaluationResult("TXN-1", "C-1", 45.0, "ALERT");
        when(riskResultRepository.findByTxnId("TXN-1")).thenReturn(result);

        mockMvc.perform(get("/api/v1/transactions/results/TXN-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compositeScore").value(45.0));
    }

    @Test
    void getResult_notFound() throws Exception {
        when(riskResultRepository.findByTxnId("MISSING")).thenReturn(null);

        mockMvc.perform(get("/api/v1/transactions/results/MISSING"))
                .andExpect(status().isNotFound());
    }

    @Test
    void evaluateTransaction_invalidTxnType_returns400() throws Exception {
        Transaction txn = TestDataFactory.createTransaction("TXN-1", "C-1", "INVALID_TYPE", 50000);

        mockMvc.perform(post("/api/v1/transactions/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(txn)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid transaction type: INVALID_TYPE"))
                .andExpect(jsonPath("$.validTypes").isArray())
                .andExpect(jsonPath("$.validTypes[0]").value("NEFT"));
    }
}
