package com.bank.anomaly.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class MetricsConfig {

    private final MeterRegistry registry;
    private final AtomicInteger silentClientCount;

    public MetricsConfig(MeterRegistry registry) {
        this.registry = registry;
        this.silentClientCount = registry.gauge("silence.active.clients", new AtomicInteger(0));
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

    public void recordSilenceDetected(String clientId) {
        Counter.builder("silence.detected.count")
                .tag("client_id", clientId)
                .register(registry)
                .increment();
    }

    public void recordSilenceResolved(String clientId) {
        Counter.builder("silence.resolved.count")
                .tag("client_id", clientId)
                .register(registry)
                .increment();
    }

    public void updateSilentClientCount(int count) {
        silentClientCount.set(count);
    }

    public void recordFeedback(String status) {
        Counter.builder("review.feedback.count")
                .tag("status", status)
                .register(registry)
                .increment();
    }

    public void recordAutoAccepted(int count) {
        Counter.builder("review.auto_accepted.count")
                .register(registry)
                .increment(count);
    }

    public void recordWeightAdjustment(String ruleId) {
        Counter.builder("review.weight_adjustment.count")
                .tag("rule_id", ruleId)
                .register(registry)
                .increment();
    }
}
