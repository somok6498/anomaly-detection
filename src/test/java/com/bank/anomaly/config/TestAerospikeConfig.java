package com.bank.anomaly.config;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.policy.Policy;
import com.aerospike.client.policy.WritePolicy;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Provides mock Aerospike beans for @SpringBootTest contexts (e.g., OpenAPI contract tests).
 * Not needed by @WebMvcTest controller tests which mock at the service layer.
 */
@TestConfiguration
public class TestAerospikeConfig {

    @Bean
    public AerospikeClient aerospikeClient() {
        return Mockito.mock(AerospikeClient.class);
    }

    @Bean("aerospikeNamespace")
    public String aerospikeNamespace() {
        return "test";
    }

    @Bean
    public WritePolicy defaultWritePolicy() {
        return new WritePolicy();
    }

    @Bean
    public Policy defaultReadPolicy() {
        return new Policy();
    }
}
