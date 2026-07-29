package com.bank.anomaly.config;

import org.springframework.context.annotation.Configuration;

/**
 * Aerospike has been replaced by Oracle + Kafka.
 * This class is retained as a placeholder to avoid breaking any remaining references.
 * All set-name constants are no longer used — tables are now in Oracle.
 */
@Configuration
public class AerospikeConfig {
    // Retained for any stale compile-time references; all runtime usage removed.
    public static final String SET_TRANSACTIONS = "transactions";
    public static final String SET_CLIENT_PROFILES = "client_profiles";
    public static final String SET_ANOMALY_RULES = "anomaly_rules";
    public static final String SET_RISK_RESULTS = "risk_results";
    public static final String SET_HOURLY_COUNTERS = "client_hourly_counters";
    public static final String SET_IF_MODELS = "if_models";
    public static final String SET_BENEFICIARY_COUNTERS = "bene_hourly_counters";
    public static final String SET_DAILY_COUNTERS = "client_daily_counters";
    public static final String SET_DAILY_BENE_COUNTERS = "daily_new_bene_cntrs";
    public static final String SET_REVIEW_QUEUE = "review_queue";
    public static final String SET_WEIGHT_HISTORY = "rule_weight_history";
    public static final String SET_AI_FEEDBACK = "ai_feedback";
    public static final String SET_METRICS_MINUTE = "metrics_minute_buckets";
    public static final String SET_METRICS_HOURLY = "metrics_hourly_buckets";
}
