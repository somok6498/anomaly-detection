package com.bank.anomaly.repository;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.BatchRead;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.bank.anomaly.config.AerospikeConfig;
import com.bank.anomaly.model.BucketEntry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Repository
public class MetricsBucketRepository {

    private static final DateTimeFormatter MINUTE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter HOUR_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC);

    private final AerospikeClient client;
    private final String namespace;

    public MetricsBucketRepository(
            AerospikeClient client,
            @Qualifier("aerospikeNamespace") String namespace) {
        this.client = client;
        this.namespace = namespace;
    }

    private static final long MAX_MINUTE_SPAN = 4 * 3600_000L;
    private static final long MAX_HOURLY_SPAN = 90 * 24 * 3600_000L;

    public List<BucketEntry> queryRange(String scope, String metric, long fromMs, long toMs) {
        long now = System.currentTimeMillis();
        if (toMs > now + 3600_000L) toMs = now + 3600_000L;
        if (fromMs < now - MAX_HOURLY_SPAN) fromMs = now - MAX_HOURLY_SPAN;
        if (fromMs >= toMs) return List.of();

        long spanMs = toMs - fromMs;
        boolean useMinute = spanMs < MAX_MINUTE_SPAN;
        String setName = useMinute ? AerospikeConfig.SET_METRICS_MINUTE : AerospikeConfig.SET_METRICS_HOURLY;
        long stepMs = useMinute ? 60_000L : 3600_000L;
        DateTimeFormatter fmt = useMinute ? MINUTE_FMT : HOUR_FMT;

        long alignedFrom = (fromMs / stepMs) * stepMs;
        long alignedTo = ((toMs + stepMs - 1) / stepMs) * stepMs;

        List<BatchRead> batchReads = new ArrayList<>();
        List<Long> timestamps = new ArrayList<>();

        for (long ts = alignedFrom; ts < alignedTo; ts += stepMs) {
            String timeBucket = fmt.format(Instant.ofEpochMilli(ts));
            String compositeKey = scope + ":" + metric + ":" + timeBucket;
            Key key = new Key(namespace, setName, compositeKey);
            batchReads.add(new BatchRead(key, true));
            timestamps.add(ts);
        }

        if (batchReads.isEmpty()) return List.of();

        client.get(null, batchReads);

        List<BucketEntry> results = new ArrayList<>();
        for (int i = 0; i < batchReads.size(); i++) {
            Record rec = batchReads.get(i).record;
            if (rec == null) continue;

            long count = rec.getLong("count");
            if (count == 0) continue;

            long rawSum = rec.getLong("sum");
            long rawMax = rec.getLong("max");
            long rawMin = rec.getLong("min");

            results.add(new BucketEntry(
                    timestamps.get(i),
                    count,
                    rawSum / 100.0,
                    rawMax == 0 ? 0 : rawMax / 100.0,
                    rawMin == 0 ? 0 : rawMin / 100.0,
                    scope,
                    metric
            ));
        }
        return results;
    }

    public List<BucketEntry> queryRangeMultiMetric(String scope, List<String> metrics,
                                                    long fromMs, long toMs) {
        List<BucketEntry> all = new ArrayList<>();
        for (String metric : metrics) {
            all.addAll(queryRange(scope, metric, fromMs, toMs));
        }
        return all;
    }
}
