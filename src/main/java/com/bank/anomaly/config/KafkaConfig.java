package com.bank.anomaly.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Value("${kafka.topics.txn-events:txn-events}")
    private String txnEventsTopic;

    @Value("${kafka.topics.profile-updates:profile-updates}")
    private String profileUpdatesTopic;

    @Bean
    public NewTopic txnEventsTopic() {
        return TopicBuilder.name(txnEventsTopic)
                .partitions(24)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic profileUpdatesTopic() {
        return TopicBuilder.name(profileUpdatesTopic)
                .partitions(24)
                .replicas(1)
                .build();
    }
}
