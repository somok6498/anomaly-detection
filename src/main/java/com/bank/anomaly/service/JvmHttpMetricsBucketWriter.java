package com.bank.anomaly.service;

import com.bank.anomaly.config.MetricsBucketWriter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.search.Search;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class JvmHttpMetricsBucketWriter {

    private final MeterRegistry registry;
    private final MetricsBucketWriter bucketWriter;

    private double prevHttpCount = 0;

    public JvmHttpMetricsBucketWriter(MeterRegistry registry, MetricsBucketWriter bucketWriter) {
        this.registry = registry;
        this.bucketWriter = bucketWriter;
    }

    @Scheduled(fixedRate = 30_000)
    public void captureMetrics() {
        captureHttpMetrics();
        captureJvmMetrics();
    }

    private void captureHttpMetrics() {
        double totalCount = Search.in(registry)
                .name("http.server.requests")
                .timers().stream()
                .mapToDouble(Timer::count)
                .sum();

        double delta = totalCount - prevHttpCount;
        if (delta > 0) {
            bucketWriter.recordCounter("SYSTEM", "http_requests", (long) delta);
        }
        prevHttpCount = totalCount;

        double p95 = Search.in(registry)
                .name("http.server.requests")
                .timers().stream()
                .mapToDouble(t -> t.percentile(0.95, TimeUnit.MILLISECONDS))
                .max().orElse(0);
        if (p95 > 0) {
            bucketWriter.recordDistribution("SYSTEM", "http_p95_ms", p95);
        }

        double p99 = Search.in(registry)
                .name("http.server.requests")
                .timers().stream()
                .mapToDouble(t -> t.percentile(0.99, TimeUnit.MILLISECONDS))
                .max().orElse(0);
        if (p99 > 0) {
            bucketWriter.recordDistribution("SYSTEM", "http_p99_ms", p99);
        }
    }

    private void captureJvmMetrics() {
        double heapUsed = registry.find("jvm.memory.used")
                .tags("area", "heap")
                .gauges().stream()
                .mapToDouble(g -> g.value())
                .sum();
        if (heapUsed > 0) {
            bucketWriter.recordDistribution("SYSTEM", "jvm_heap_used", heapUsed);
        }

        double heapMax = registry.find("jvm.memory.max")
                .tags("area", "heap")
                .gauges().stream()
                .mapToDouble(g -> g.value())
                .sum();
        if (heapMax > 0) {
            bucketWriter.recordDistribution("SYSTEM", "jvm_heap_max", heapMax);
        }
    }
}
