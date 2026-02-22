package com.bank.anomaly.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class MetricsConfig {

    private final MeterRegistry registry;

    public MetricsConfig(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordEvaluation(String action, double compositeScore) {
        Counter.builder("evaluation.count")
                .tag("action", action)
                .register(registry)
                .increment();

        DistributionSummary.builder("evaluation.composite_score")
                .tag("action", action)
                .register(registry)
                .record(compositeScore);
    }

    public void recordRuleTriggered(String ruleType) {
        Counter.builder("rule.triggered.count")
                .tag("rule_type", ruleType)
                .register(registry)
                .increment();
    }

    public void recordNotification(String channel, String status) {
        Counter.builder("notification.sent.count")
                .tag("channel", channel)
                .tag("status", status)
                .register(registry)
                .increment();
    }
}
