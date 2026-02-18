package com.bank.anomaly.model;

public enum RuleType {
    TRANSACTION_TYPE_ANOMALY,
    TPS_SPIKE,
    AMOUNT_ANOMALY,
    HOURLY_AMOUNT_ANOMALY,
    AMOUNT_PER_TYPE_ANOMALY,
    ISOLATION_FOREST
}
