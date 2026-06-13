package com.bank.anomaly.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class MetricsConfig {

    private final MeterRegistry registry;
    private final MetricsBucketWriter bucketWriter;
    private final AtomicInteger silentClientCount;
    private final Map<String, AtomicLong> lastTxnGauges = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> silenceStateGauges = new ConcurrentHashMap<>();

    public MetricsConfig(MeterRegistry registry, MetricsBucketWriter bucketWriter) {
        this.registry = registry;
        this.bucketWriter = bucketWriter;
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

        bucketWriter.recordCounter("SYSTEM", "eval_count_" + action, 1);
        bucketWriter.recordDistribution("SYSTEM", "eval_score_" + action, compositeScore);
    }

    public void recordRuleTriggered(String ruleType) {
        Counter.builder("rule.triggered.count")
                .tag("rule_type", ruleType)
                .register(registry)
                .increment();

        bucketWriter.recordCounter("SYSTEM", "rule_" + ruleType, 1);
    }

    public void recordNotification(String channel, String status) {
        Counter.builder("notification.sent.count")
                .tag("channel", channel)
                .tag("status", status)
                .register(registry)
                .increment();

        bucketWriter.recordCounter("SYSTEM", "notif_" + channel + "_" + status, 1);
    }

    public void recordSilenceDetected(String clientId, long lastTxnEpochMs) {
        Counter.builder("silence.detected.count")
                .tag("client_id", clientId)
                .register(registry)
                .increment();

        lastTxnGauges.computeIfAbsent(clientId, id -> {
            AtomicLong holder = new AtomicLong(lastTxnEpochMs);
            Gauge.builder("silence.last.txn.epoch", holder, AtomicLong::doubleValue)
                    .tag("client_id", id)
                    .description("Epoch millis of last transaction for silent client")
                    .register(registry);
            return holder;
        }).set(lastTxnEpochMs);

        bucketWriter.recordCounter("SYSTEM", "silence_detected", 1);
        bucketWriter.recordCounter(clientId, "silence_detected", 1);
    }

    public void recordSilenceResolved(String clientId) {
        Counter.builder("silence.resolved.count")
                .tag("client_id", clientId)
                .register(registry)
                .increment();

        bucketWriter.recordCounter("SYSTEM", "silence_resolved", 1);
        bucketWriter.recordCounter(clientId, "silence_resolved", 1);
    }

    public void updateSilentClientCount(int count) {
        silentClientCount.set(count);
    }

    public void recordFeedback(String status) {
        Counter.builder("review.feedback.count")
                .tag("status", status)
                .register(registry)
                .increment();

        bucketWriter.recordCounter("SYSTEM", "feedback_" + status, 1);
    }

    public void recordAutoAccepted(int count) {
        Counter.builder("review.auto_accepted.count")
                .register(registry)
                .increment(count);

        bucketWriter.recordCounter("SYSTEM", "auto_accepted", count);
    }

    public void recordWeightAdjustment(String ruleId) {
        Counter.builder("review.weight_adjustment.count")
                .tag("rule_id", ruleId)
                .register(registry)
                .increment();

        bucketWriter.recordCounter("SYSTEM", "weight_adj", 1);
    }

    // --- Per-client metrics for Grafana client dashboard ---

    public void recordClientEvaluation(String clientId, String action, double compositeScore) {
        Counter.builder("client.evaluation.count")
                .tag("client_id", clientId)
                .tag("action", action)
                .register(registry)
                .increment();

        DistributionSummary.builder("client.evaluation.composite_score")
                .tag("client_id", clientId)
                .tag("action", action)
                .register(registry)
                .record(compositeScore);

        silenceStateGauges.computeIfAbsent(clientId, id -> {
            AtomicInteger holder = new AtomicInteger(0);
            Gauge.builder("client.silence.state", holder, AtomicInteger::doubleValue)
                    .tag("client_id", id)
                    .description("1 if client is currently silent, 0 if active")
                    .register(registry);
            return holder;
        });

        bucketWriter.recordCounter(clientId, "eval_count_" + action, 1);
        bucketWriter.recordDistribution(clientId, "eval_score_" + action, compositeScore);
    }

    public void recordClientTransactionAmount(String clientId, String txnType, double amount) {
        DistributionSummary.builder("client.transaction.amount")
                .tag("client_id", clientId)
                .tag("txn_type", txnType)
                .register(registry)
                .record(amount);

        bucketWriter.recordDistribution(clientId, "txn_amount_" + txnType, amount);
        bucketWriter.recordDistribution("SYSTEM", "txn_amount_" + txnType, amount);
    }

    public void recordClientTransactionType(String clientId, String txnType) {
        Counter.builder("client.transaction.type.count")
                .tag("client_id", clientId)
                .tag("txn_type", txnType)
                .register(registry)
                .increment();

        bucketWriter.recordCounter(clientId, "txn_type_" + txnType, 1);
        bucketWriter.recordCounter("SYSTEM", "txn_type_" + txnType, 1);
    }

    public void recordClientRuleTriggered(String clientId, String ruleType) {
        Counter.builder("client.rule.triggered.count")
                .tag("client_id", clientId)
                .tag("rule_type", ruleType)
                .register(registry)
                .increment();

        bucketWriter.recordCounter(clientId, "rule_" + ruleType, 1);
    }

    public void updateClientSilenceState(String clientId, boolean isSilent) {
        silenceStateGauges.computeIfAbsent(clientId, id -> {
            AtomicInteger holder = new AtomicInteger(0);
            Gauge.builder("client.silence.state", holder, AtomicInteger::doubleValue)
                    .tag("client_id", id)
                    .description("1 if client is currently silent, 0 if active")
                    .register(registry);
            return holder;
        }).set(isSilent ? 1 : 0);
    }
}
