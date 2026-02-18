package com.bank.anomaly.service;

import com.bank.anomaly.model.Transaction;
import com.bank.anomaly.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;

    public TransactionService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    public Transaction getTransaction(String txnId) {
        return transactionRepository.findByTxnId(txnId);
    }

    public List<Transaction> getTransactionsByClientId(String clientId, int limit) {
        return transactionRepository.findByClientId(clientId, limit);
    }
}
