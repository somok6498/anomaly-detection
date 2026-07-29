package com.bank.anomaly.repository;

import com.bank.anomaly.model.BucketEntry;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private final JdbcTemplate jdbc;

    public MetricsBucketRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
        String granularity = useMinute ? "MINUTE" : "HOURLY";
        long stepMs = useMinute ? 60_000L : 3600_000L;
        DateTimeFormatter fmt = useMinute ? MINUTE_FMT : HOUR_FMT;

        long alignedFrom = (fromMs / stepMs) * stepMs;
        long alignedTo = ((toMs + stepMs - 1) / stepMs) * stepMs;

        List<String> buckets = new ArrayList<>();
        List<Long> timestamps = new ArrayList<>();
        for (long ts = alignedFrom; ts < alignedTo; ts += stepMs) {
            buckets.add(fmt.format(Instant.ofEpochMilli(ts)));
            timestamps.add(ts);
        }

        if (buckets.isEmpty()) return List.of();

        List<BucketEntry> results = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();

        for (int i = 0; i < buckets.size(); i++) {
            String bucket = buckets.get(i);
            long ts = timestamps.get(i);
            jdbc.query("""
                    SELECT count_val, sum_val, max_val, min_val FROM metrics_buckets
                    WHERE scope = ? AND metric = ? AND bucket = ? AND granularity = ?
                    """, (rs) -> {
                long count = rs.getLong("count_val");
                if (count > 0) {
                    results.add(new BucketEntry(
                            ts, count,
                            rs.getLong("sum_val") / 100.0,
                            rs.getLong("max_val") == 0 ? 0 : rs.getLong("max_val") / 100.0,
                            rs.getLong("min_val") == 0 ? 0 : rs.getLong("min_val") / 100.0,
                            scope, metric));
                }
            }, scope, metric, bucket, granularity);
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
