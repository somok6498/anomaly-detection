package com.bank.anomaly.config;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Operation;
import com.aerospike.client.policy.WritePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MetricsBucketWriter {

    private static final Logger log = LoggerFactory.getLogger(MetricsBucketWriter.class);

    private static final DateTimeFormatter MINUTE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter HOUR_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC);

    private final AerospikeClient client;
    private final String namespace;
    private final WritePolicy minutePolicy;
    private final WritePolicy hourlyPolicy;

    private final ConcurrentHashMap<String, double[]> minuteAccum = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, double[]> hourlyAccum = new ConcurrentHashMap<>();

    public MetricsBucketWriter(
            AerospikeClient client,
            @Qualifier("aerospikeNamespace") String namespace,
            @Qualifier("minuteBucketWritePolicy") WritePolicy minutePolicy,
            @Qualifier("hourlyBucketWritePolicy") WritePolicy hourlyPolicy) {
        this.client = client;
        this.namespace = namespace;
        this.minutePolicy = minutePolicy;
        this.hourlyPolicy = hourlyPolicy;
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
        flushMap(minuteAccum, AerospikeConfig.SET_METRICS_MINUTE, minutePolicy);
        flushMap(hourlyAccum, AerospikeConfig.SET_METRICS_HOURLY, hourlyPolicy);
    }

    public void flushNow() {
        flush();
    }

    private void flushMap(ConcurrentHashMap<String, double[]> map, String setName, WritePolicy policy) {
        var snapshot = new ConcurrentHashMap<>(map);
        map.clear();

        snapshot.forEach((compositeKey, vals) -> {
            try {
                String[] parts = parseCompositeKey(compositeKey);
                String scope = parts[0];
                String metric = parts[1];
                String timeBucket = parts[2];
                long ts = bucketToEpochMs(timeBucket);

                Key asKey = new Key(namespace, setName, compositeKey);

                long count = (long) vals[0];
                double sum = vals[1];
                double max = vals[2];
                double min = vals[3];

                if (max == Double.NEGATIVE_INFINITY) {
                    client.operate(policy, asKey,
                            Operation.add(new Bin("count", count)),
                            Operation.put(new Bin("scope", scope)),
                            Operation.put(new Bin("metric", metric)),
                            Operation.put(new Bin("ts", ts)));
                } else {
                    client.operate(policy, asKey,
                            Operation.add(new Bin("count", count)),
                            Operation.add(new Bin("sum", Math.round(sum * 100))),
                            Operation.put(new Bin("scope", scope)),
                            Operation.put(new Bin("metric", metric)),
                            Operation.put(new Bin("ts", ts)));
                    updateMaxMin(asKey, policy, max, min);
                }
            } catch (Exception e) {
                log.warn("Failed to flush bucket {}: {}", compositeKey, e.getMessage());
            }
        });
    }

    private void updateMaxMin(Key key, WritePolicy policy, double newMax, double newMin) {
        try {
            var record = client.get(null, key, "max", "min");
            double existingMax = Double.NEGATIVE_INFINITY;
            double existingMin = Double.POSITIVE_INFINITY;
            if (record != null) {
                Long rawMax = record.getLong("max");
                Long rawMin = record.getLong("min");
                if (rawMax != null && rawMax != 0) existingMax = rawMax / 100.0;
                if (rawMin != null && rawMin != 0) existingMin = rawMin / 100.0;
            }
            double finalMax = Math.max(existingMax, newMax);
            double finalMin = Math.min(existingMin, newMin);
            client.operate(policy, key,
                    Operation.put(new Bin("max", Math.round(finalMax * 100))),
                    Operation.put(new Bin("min", Math.round(finalMin * 100))));
        } catch (Exception e) {
            log.warn("Failed to update max/min for {}: {}", key, e.getMessage());
        }
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
