package com.bank.anomaly.config;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.policy.ClientPolicy;
import com.aerospike.client.policy.Policy;
import com.aerospike.client.policy.WritePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AerospikeConfig {

    public static final String SET_TRANSACTIONS = "transactions";
    public static final String SET_CLIENT_PROFILES = "client_profiles";
    public static final String SET_ANOMALY_RULES = "anomaly_rules";
    public static final String SET_RISK_RESULTS = "risk_results";
    public static final String SET_HOURLY_COUNTERS = "client_hourly_counters";
    public static final String SET_IF_MODELS = "if_models";
    public static final String SET_BENEFICIARY_COUNTERS = "bene_hourly_counters";
    public static final String SET_DAILY_COUNTERS = "client_daily_counters";
    public static final String SET_DAILY_BENE_COUNTERS = "daily_new_bene_cntrs";

    @Value("${aerospike.host:127.0.0.1}")
    private String host;

    @Value("${aerospike.port:3000}")
    private int port;

    @Value("${aerospike.namespace:banking}")
    private String namespace;

    @Bean
    public AerospikeClient aerospikeClient() {
        ClientPolicy clientPolicy = new ClientPolicy();
        clientPolicy.maxConnsPerNode = 300;
        clientPolicy.timeout = 5000;

        // Read policy defaults
        clientPolicy.readPolicyDefault.totalTimeout = 3000;
        clientPolicy.readPolicyDefault.socketTimeout = 1000;

        // Write policy defaults
        clientPolicy.writePolicyDefault.totalTimeout = 3000;
        clientPolicy.writePolicyDefault.socketTimeout = 1000;

        return new AerospikeClient(clientPolicy, host, port);
    }

    @Bean
    public WritePolicy defaultWritePolicy() {
        WritePolicy policy = new WritePolicy();
        policy.totalTimeout = 3000;
        policy.socketTimeout = 1000;
        return policy;
    }

    @Bean
    public Policy defaultReadPolicy() {
        Policy policy = new Policy();
        policy.totalTimeout = 3000;
        policy.socketTimeout = 1000;
        return policy;
    }

    @Bean
    public String aerospikeNamespace() {
        return namespace;
    }
}
