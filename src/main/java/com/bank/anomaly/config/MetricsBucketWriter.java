package com.bank.anomaly.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MetricsBucketWriter {

    private static final Logger log = LoggerFactory.getLogger(MetricsBucketWriter.class);

    private static final DateTimeFormatter MINUTE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter HOUR_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbc;

    private final ConcurrentHashMap<String, double[]> minuteAccum = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, double[]> hourlyAccum = new ConcurrentHashMap<>();

    public MetricsBucketWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void recordCounter(String scope, String metric, long increment) {
        long now = System.currentTimeMillis();
        accumulate(minuteAccum, minuteKey(scope, metric, now), increment, 0, false);
        accumulate(hourlyAccum, hourlyKey(scope, metric, now), increment, 0, false);
    }

    public void recordDistribution(String scope, String metric, double value) {
        long now = System.currentTimeMillis();
        accumulate(minuteAccum, minuteKey(scope, metric, now), 1, value, true);
        accumulate(hourlyAccum, hourlyKey(scope, metric, now), 1, value, true);
    }

    public void recordCounterAt(String scope, String metric, long increment, long timestampMs) {
        accumulate(hourlyAccum, hourlyKey(scope, metric, timestampMs), increment, 0, false);
    }

    public void recordDistributionAt(String scope, String metric, double value, long timestampMs) {
        accumulate(hourlyAccum, hourlyKey(scope, metric, timestampMs), 1, value, true);
    }

    private void accumulate(ConcurrentHashMap<String, double[]> map, String key,
                            long countInc, double value, boolean trackDist) {
        map.compute(key, (k, arr) -> {
            if (arr == null) {
                arr = new double[]{0, 0, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY};
            }
            arr[0] += countInc;
            if (trackDist) {
                arr[1] += value;
                if (value > arr[2]) arr[2] = value;
                if (value < arr[3]) arr[3] = value;
            }
            return arr;
        });
    }

    @Scheduled(fixedRate = 10_000)
    public void flush() {
        flushMap(minuteAccum, "MINUTE");
        flushMap(hourlyAccum, "HOURLY");
    }

    public void flushNow() {
        flush();
    }

    private void flushMap(ConcurrentHashMap<String, double[]> map, String granularity) {
        var snapshot = new ConcurrentHashMap<>(map);
        map.clear();

        snapshot.forEach((compositeKey, vals) -> {
            try {
                String[] parts = parseCompositeKey(compositeKey);
                String scope = parts[0];
                String metric = parts[1];
                String timeBucket = parts[2];

                long count = (long) vals[0];
                long sumCents = Math.round(vals[1] * 100);
                long maxCents = vals[2] == Double.NEGATIVE_INFINITY ? 0 : Math.round(vals[2] * 100);
                long minCents = vals[3] == Double.POSITIVE_INFINITY ? 0 : Math.round(vals[3] * 100);

                jdbc.update("""
                        MERGE INTO metrics_buckets m
                        USING (SELECT ? AS scope, ? AS metric, ? AS bucket, ? AS granularity FROM dual) s
                        ON (m.scope = s.scope AND m.metric = s.metric AND m.bucket = s.bucket AND m.granularity = s.granularity)
                        WHEN MATCHED THEN UPDATE SET
                            count_val = count_val + ?,
                            sum_val = sum_val + ?,
                            max_val = GREATEST(max_val, ?),
                            min_val = LEAST(CASE WHEN min_val = 0 THEN ? ELSE min_val END, ?)
                        WHEN NOT MATCHED THEN INSERT (scope, metric, bucket, granularity, count_val, sum_val, max_val, min_val)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        scope, metric, timeBucket, granularity,
                        count, sumCents, maxCents, minCents, minCents,
                        scope, metric, timeBucket, granularity, count, sumCents, maxCents, minCents);
            } catch (Exception e) {
                log.warn("Failed to flush bucket {}: {}", compositeKey, e.getMessage());
            }
        });
    }

    private String minuteKey(String scope, String metric, long epochMs) {
        return scope + ":" + metric + ":" + MINUTE_FMT.format(Instant.ofEpochMilli(epochMs));
    }

    private String hourlyKey(String scope, String metric, long epochMs) {
        return scope + ":" + metric + ":" + HOUR_FMT.format(Instant.ofEpochMilli(epochMs));
    }

    static String[] parseCompositeKey(String key) {
        int lastColon = key.lastIndexOf(':');
        String timeBucket = key.substring(lastColon + 1);
        String rest = key.substring(0, lastColon);
        int firstColon = rest.indexOf(':');
        String scope = rest.substring(0, firstColon);
        String metric = rest.substring(firstColon + 1);
        return new String[]{scope, metric, timeBucket};
    }

    static long bucketToEpochMs(String bucket) {
        if (bucket.length() == 12) {
            return java.time.LocalDateTime.parse(bucket,
                    DateTimeFormatter.ofPattern("yyyyMMddHHmm"))
                    .toInstant(ZoneOffset.UTC).toEpochMilli();
        } else {
            return java.time.LocalDateTime.parse(bucket + "00",
                    DateTimeFormatter.ofPattern("yyyyMMddHHmm"))
                    .toInstant(ZoneOffset.UTC).toEpochMilli();
        }
    }
}
