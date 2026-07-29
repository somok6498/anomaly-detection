# Anomaly Detection — Full Oracle + Kafka Redesign

**Date:** 2026-06-14 (updated 2026-07-27)
**Status:** Draft — awaiting team review
**Replaces:** Aerospike-based architecture (all sets)
**Stack:** Oracle 19c (RDS), Amazon MSK (Kafka), Spring Boot 3.2.5, Spring Data JDBC, Java 17
**Incremental infra cost:** ~$12/month (gp3 storage only — Oracle RDS instance/license and MSK already available)

---

## 1. Design Principles

1. **Zero full-table scans.** Every query runs on indexed columns. Partitioned tables where Oracle EE is available; indexed deletes otherwise (see Section 4 RDS note).
2. **Single-writer per client.** Kafka (MSK) partitioning by `clientId` guarantees one consumer pod per client at any time — eliminates deadlocks and lost updates on profiles.
3. **Async-first.** HTTP thread saves the transaction and publishes to Kafka. Profile building, rule evaluation, and scoring happen in the consumer — off the API thread.
4. **Narrow tables, no JSON blobs.** Beneficiary stats, seasonal stats, and type stats are normalized into separate tables with fixed-width rows. No unbounded columns.
5. **Batch writes to Oracle.** In-memory accumulation per consumer thread, flushed every 5 seconds. At 200 TPS across 200 clients, this collapses ~2,000 individual DML/sec to one batch cycle.
6. **Optimistic locking everywhere.** Every mutable table has a `version` column. `UPDATE ... WHERE version = ?` — no pessimistic locks, no `SELECT ... FOR UPDATE`, no deadlocks.

---

## 2. Scale & Sizing

| Metric | Value |
|--------|-------|
| Clients | 200 |
| Transactions per day | 20,00,000 (20 lakh) |
| Peak TPS | 200-250 (concentrated in business hours) |
| Average TPS (24h) | ~23 |
| Transaction retention | 30 days |
| Evaluation result retention | 30 days |
| Beneficiary retention | 90 days (inactive eviction) |
| Max pods (K8s) | 8 |
| Kafka partitions | 24 (3x max pods) |

### Storage Estimates (30-day window)

| Table | Rows | Row Size | Total |
|-------|------|----------|-------|
| `transactions` | 6,00,00,000 (6 crore) | ~300 bytes | ~18 GB |
| `evaluation_results` | 6,00,00,000 | ~250 bytes | ~15 GB |
| `client_profiles` | 200 | ~200 bytes | ~40 KB |
| `client_type_stats` | 1,000 (200 × 5 types) | ~60 bytes | ~60 KB |
| `client_seasonal_stats` | 6,200 (200 × 31 slots) | ~80 bytes | ~500 KB |
| `beneficiary_stats` | ~5,00,000 (est. 2,500/client) | ~80 bytes | ~40 MB |
| `client_counters` | ~4,00,000 (200 clients × 7 types × ~300 buckets) | ~50 bytes | ~20 MB |
| `review_queue` | ~50,000 (est. ALERT/BLOCK subset) | ~200 bytes | ~10 MB |
| **Total** | | | **~33 GB** |

Fits comfortably on an RDS `db.r5.xlarge` (32 GB RAM) or larger. The `transactions` and `evaluation_results` tables dominate — everything else is negligible.

---

## 3. High-Level Architecture

```
                        ┌──────────────────────────────────────┐
                        │        AWS ALB / NLB                 │
                        └───┬────┬────┬────┬────┬──────────────┘
                            │    │    │    │    │   ... up to 8 pods
                    ┌───────▼──┐ │ ┌──▼────▼──┐ │
                    │  Pod 1   │ │ │  Pod N    │ │
                    │ REST API │ │ │ REST API  │ │
                    │(stateless│ │ │(stateless)│ │
                    └────┬─────┘ │ └────┬──────┘ │
                         │       │      │        │
          ┌──────────────▼───────▼──────▼────────▼──────────────┐
          │              Amazon MSK (Kafka)                      │
          │  Topic: txn-events, 24 partitions, key = clientId   │
          │  Replication: 3, Retention: 7 days, Compression: lz4│
          └──────┬────────────────────────────────┬─────────────┘
                 │                                │
      ┌──────────▼──────────┐          ┌──────────▼──────────┐
      │ Consumer (Pod 1)    │          │ Consumer (Pod N)    │
      │ partitions 0-2      │   ...    │ partitions 21-23    │
      │                     │          │                     │
      │ Local cache:        │          │ Local cache:        │
      │ • profiles (dirty)  │          │ • profiles (dirty)  │
      │ • counters (deltas) │          │ • counters (deltas) │
      │ • bene stats (dirty)│          │ • bene stats (dirty)│
      │                     │          │                     │
      │ Flush every 5s ─────┼──────────┼──────┐              │
      └─────────────────────┘          └──────┼──────────────┘
                                              │
                                    ┌─────────▼───────────────┐
                                    │  Oracle 19c (AWS RDS)   │
                                    │                         │
                                    │  11 tables              │
                                    │  Optimistic locking     │
                                    │  No full-table scans    │
                                    │  HikariCP: 20 conn/pod  │
                                    └─────────────────────────┘
```

### Request Flow

```
1. POST /api/v1/transactions/evaluate
   │
   ├─ Validate transaction (bean validation, type checks)
   ├─ INSERT into TRANSACTIONS table (Oracle RDS)
   ├─ Publish to MSK topic "txn-events" (key = clientId)
   └─ Return 202 Accepted { txnId, status: "QUEUED" }
       Response time: < 10ms

2. Kafka Consumer (one partition = one thread = one set of clients)
   │
   ├─ Read profile from local cache (ConcurrentHashMap)
   │   └─ Cache miss? SELECT from Oracle, populate cache
   ├─ Read counters from local cache
   │   └─ Cache miss? SELECT from Oracle, populate cache
   ├─ Update profile + counters in memory
   ├─ Evaluate rules → compute composite score
   ├─ INSERT evaluation result into Oracle (immediate, write-once)
   ├─ If ALERT/BLOCK: INSERT into review queue (immediate)
   ├─ Mark profile + counters as dirty
   └─ Every 5s: batch-flush all dirty profiles/counters to Oracle

3. Dashboard/API reads
   │
   └─ Always read from Oracle (source of truth for queries)
       All queries hit indexed columns, no profile writes in read path
```

---

## 4. Oracle Schema Design

### Important: RDS Partitioning Availability

Oracle table partitioning requires **Enterprise Edition (EE) with the Partitioning option** on RDS. This is a significant cost uplift over Standard Edition 2 (SE2).

**If EE + Partitioning is available:** Use interval-partitioned tables for `transactions` and `evaluation_results` as shown below. Retention via instant `ALTER TABLE DROP PARTITION`.

**If SE2 (no partitioning):** Use regular (non-partitioned) tables with B-tree indexes. Retention via indexed DELETE + `ALTER TABLE SHRINK SPACE`. At 6 crore rows/month with proper indexes, Oracle handles this without partitioning — queries still use index range scans. The tradeoff is DELETE-based retention (minutes of redo log) vs DROP PARTITION (instant). Both schemas are provided below.

### 4.1 TRANSACTIONS

The immutable ledger. Write-once, never updated.

**With partitioning (EE):**

```sql
CREATE TABLE transactions (
    txn_id          VARCHAR2(64)    NOT NULL,
    client_id       VARCHAR2(32)    NOT NULL,
    amount          NUMBER(15,2)    NOT NULL,
    txn_type        VARCHAR2(20)    NOT NULL,
    beneficiary_id  VARCHAR2(128),
    channel         VARCHAR2(20),
    location        VARCHAR2(100),
    device_id       VARCHAR2(64),
    description     VARCHAR2(500),
    created_at      TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT pk_transactions PRIMARY KEY (txn_id, created_at)
)
PARTITION BY RANGE (created_at) INTERVAL (NUMTODSINTERVAL(1, 'DAY'))
(
    PARTITION p_initial VALUES LESS THAN (TIMESTAMP '2026-06-01 00:00:00')
);

CREATE INDEX idx_txn_client_time ON transactions (client_id, created_at) LOCAL;
```

**Without partitioning (SE2):**

```sql
CREATE TABLE transactions (
    txn_id          VARCHAR2(64)    NOT NULL,
    client_id       VARCHAR2(32)    NOT NULL,
    amount          NUMBER(15,2)    NOT NULL,
    txn_type        VARCHAR2(20)    NOT NULL,
    beneficiary_id  VARCHAR2(128),
    channel         VARCHAR2(20),
    location        VARCHAR2(100),
    device_id       VARCHAR2(64),
    description     VARCHAR2(500),
    created_at      TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT pk_transactions PRIMARY KEY (txn_id)
);

CREATE INDEX idx_txn_client_time ON transactions (client_id, created_at);
CREATE INDEX idx_txn_created ON transactions (created_at);
```

**Notes:**
- `idx_txn_created` (SE2 only) supports retention DELETE: `DELETE FROM transactions WHERE created_at < :thirtyDaysAgo`. Index range scan, not full table scan.
- **No full scan.** All queries use `txn_id` (PK) or `client_id + created_at` (index).
- 20 lakh rows/day × 30 days = 6 crore rows. With B-tree indexes, point lookups remain sub-ms; range scans on `client_id + created_at` are partition-pruned (EE) or index-range-scanned (SE2).

### 4.2 CLIENT_PROFILES

One row per client. Fixed-width. Always accessed by PK (`client_id`). Optimistic locking via `version`.

```sql
CREATE TABLE client_profiles (
    client_id               VARCHAR2(32)    NOT NULL,
    total_txn_count         NUMBER(12)      DEFAULT 0,

    -- EWMA global stats
    ewma_amount             NUMBER(15,4)    DEFAULT 0,
    amount_m2               NUMBER(20,6)    DEFAULT 0,

    -- Hourly TPS stats
    ewma_hourly_tps         NUMBER(12,4)    DEFAULT 0,
    tps_m2                  NUMBER(16,6)    DEFAULT 0,
    completed_hours_count   NUMBER(8)       DEFAULT 0,

    -- Hourly amount stats
    ewma_hourly_amount      NUMBER(15,4)    DEFAULT 0,
    hourly_amount_m2        NUMBER(20,6)    DEFAULT 0,

    -- Daily stats
    ewma_daily_amount       NUMBER(15,4)    DEFAULT 0,
    daily_amount_m2         NUMBER(20,6)    DEFAULT 0,
    completed_days_count    NUMBER(8)       DEFAULT 0,

    -- Daily new beneficiary stats
    ewma_daily_new_bene     NUMBER(12,4)    DEFAULT 0,
    daily_new_bene_m2       NUMBER(16,6)    DEFAULT 0,
    completed_days_bene_cnt NUMBER(8)       DEFAULT 0,

    -- Beneficiary summary (count only, detail in beneficiary_stats)
    distinct_bene_count     NUMBER(8)       DEFAULT 0,

    -- Bucket tracking
    last_hour_bucket        VARCHAR2(12),
    last_day_bucket         VARCHAR2(10),

    -- Metadata
    last_updated            TIMESTAMP       DEFAULT SYSTIMESTAMP,
    version                 NUMBER(8)       DEFAULT 0 NOT NULL,
    created_at              TIMESTAMP       DEFAULT SYSTIMESTAMP,

    CONSTRAINT pk_client_profiles PRIMARY KEY (client_id)
);
```

**Notes:**
- 200 clients × ~200 bytes/row = ~40 KB. Entire table lives in Oracle buffer cache permanently.
- `version` column for optimistic locking: `UPDATE ... SET version = version + 1 WHERE client_id = ? AND version = ?`
- **No JSON columns.** All stats are individual numeric columns — queryable, indexable, no deserialization overhead.
- PK-only access. No secondary indexes needed.

### 4.3 CLIENT_TYPE_STATS

Per-client, per-transaction-type statistics. Bounded to ~5 rows per client (one per configured txn type).

```sql
CREATE TABLE client_type_stats (
    client_id       VARCHAR2(32)    NOT NULL,
    txn_type        VARCHAR2(20)    NOT NULL,
    txn_count       NUMBER(12)      DEFAULT 0,
    avg_amount      NUMBER(15,4)    DEFAULT 0,
    amount_m2       NUMBER(20,6)    DEFAULT 0,
    amount_count    NUMBER(12)      DEFAULT 0,

    CONSTRAINT pk_client_type_stats PRIMARY KEY (client_id, txn_type)
);
```

### 4.4 CLIENT_SEASONAL_STATS

Per-client seasonal patterns. 31 rows per client (24 hourly + 7 daily). Fixed and bounded.

```sql
CREATE TABLE client_seasonal_stats (
    client_id       VARCHAR2(32)    NOT NULL,
    period_type     VARCHAR2(6)     NOT NULL,   -- 'HOURLY' or 'DAILY'
    slot            NUMBER(2)       NOT NULL,   -- 0-23 for hourly, 1-7 for daily
    tps_mean        NUMBER(12,4)    DEFAULT 0,
    tps_m2          NUMBER(16,6)    DEFAULT 0,
    tps_count       NUMBER(8)       DEFAULT 0,
    amt_mean        NUMBER(15,4)    DEFAULT 0,
    amt_m2          NUMBER(20,6)    DEFAULT 0,
    amt_count       NUMBER(8)       DEFAULT 0,

    CONSTRAINT pk_seasonal_stats PRIMARY KEY (client_id, period_type, slot)
);
```

### 4.5 BENEFICIARY_STATS

One row per client-beneficiary pair. **This is where the 168KB blob is decomposed into individual lightweight rows.**

```sql
CREATE TABLE beneficiary_stats (
    client_id       VARCHAR2(32)    NOT NULL,
    beneficiary_id  VARCHAR2(128)   NOT NULL,
    txn_count       NUMBER(8)       DEFAULT 0,
    total_amount    NUMBER(15,2)    DEFAULT 0,
    last_seen       TIMESTAMP       DEFAULT SYSTIMESTAMP,

    CONSTRAINT pk_bene_stats PRIMARY KEY (client_id, beneficiary_id)
);

CREATE INDEX idx_bene_reverse ON beneficiary_stats (beneficiary_id, client_id);
CREATE INDEX idx_bene_last_seen ON beneficiary_stats (last_seen);
```

**Notes:**
- ~80 bytes per row vs 168KB monolithic record. A client with 2,500 beneficiaries = 2,500 rows × 80 bytes = ~200 KB spread across individually addressable rows.
- **MERGE for upsert** — no read-before-write.
- Cleanup job: `DELETE FROM beneficiary_stats WHERE last_seen < SYSTIMESTAMP - INTERVAL '90' DAY` uses `idx_bene_last_seen`.
- **No EWMA per beneficiary.** Simplified to `txn_count` + `total_amount`. Per-beneficiary variance tracking was the primary record-size driver and is not needed for the current rule set. If needed later, add `avg_amount` and `amount_m2` columns (2 more numbers, still ~100 bytes/row).

### 4.6 CLIENT_COUNTERS

Hourly and daily counters. Replaces 4 Aerospike counter sets with one table.

**With partitioning (EE):**

```sql
CREATE TABLE client_counters (
    client_id       VARCHAR2(32)    NOT NULL,
    counter_type    VARCHAR2(20)    NOT NULL,
    bucket          VARCHAR2(12)    NOT NULL,
    count_val       NUMBER(12)      DEFAULT 0,
    sum_val         NUMBER(15,2)    DEFAULT 0,

    CONSTRAINT pk_client_counters PRIMARY KEY (client_id, counter_type, bucket)
)
PARTITION BY HASH (client_id) PARTITIONS 8;
```

**Without partitioning (SE2):** Same DDL without the `PARTITION BY` clause. Hash partitioning is a nice-to-have for even I/O distribution but not required at 200 clients.

**Counter types:**

| counter_type | bucket format | Purpose |
|---|---|---|
| `HOURLY_TXN` | `yyyyMMddHH` | Hourly transaction count |
| `HOURLY_AMT` | `yyyyMMddHH` | Hourly transaction amount sum |
| `DAILY_TXN` | `yyyyMMdd` | Daily transaction count |
| `DAILY_AMT` | `yyyyMMdd` | Daily transaction amount sum |
| `DAILY_NEW_BENE` | `yyyyMMdd` | Daily new beneficiary count |
| `BENE_HOURLY_TXN` | `yyyyMMddHH` | Beneficiary hourly txn count |
| `BENE_HOURLY_AMT` | `yyyyMMddHH` | Beneficiary hourly amount sum |

**MERGE for atomic upsert** (no read-before-write):

```sql
MERGE INTO client_counters c
USING (SELECT :clientId AS client_id, :type AS counter_type,
              :bucket AS bucket, :delta AS delta, :sumDelta AS sum_delta
       FROM dual) s
ON (c.client_id = s.client_id AND c.counter_type = s.counter_type AND c.bucket = s.bucket)
WHEN MATCHED THEN
    UPDATE SET c.count_val = c.count_val + s.delta,
               c.sum_val = c.sum_val + s.sum_delta
WHEN NOT MATCHED THEN
    INSERT (client_id, counter_type, bucket, count_val, sum_val)
    VALUES (s.client_id, s.counter_type, s.bucket, s.delta, s.sum_delta);
```

### 4.7 EVALUATION_RESULTS

Write-once evaluation outcomes.

**With partitioning (EE):**

```sql
CREATE TABLE evaluation_results (
    result_id       VARCHAR2(64)    NOT NULL,
    txn_id          VARCHAR2(64)    NOT NULL,
    client_id       VARCHAR2(32)    NOT NULL,
    composite_score NUMBER(6,2),
    action          VARCHAR2(10)    NOT NULL,
    triggered_rules VARCHAR2(2000),
    rule_details    CLOB,
    evaluated_at    TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT pk_eval_results PRIMARY KEY (result_id, evaluated_at)
)
PARTITION BY RANGE (evaluated_at) INTERVAL (NUMTODSINTERVAL(1, 'DAY'))
(
    PARTITION p_initial VALUES LESS THAN (TIMESTAMP '2026-06-01 00:00:00')
);

CREATE INDEX idx_eval_client_time ON evaluation_results (client_id, evaluated_at) LOCAL;
CREATE INDEX idx_eval_action_time ON evaluation_results (action, evaluated_at) LOCAL;
```

**Without partitioning (SE2):**

```sql
CREATE TABLE evaluation_results (
    result_id       VARCHAR2(64)    NOT NULL,
    txn_id          VARCHAR2(64)    NOT NULL,
    client_id       VARCHAR2(32)    NOT NULL,
    composite_score NUMBER(6,2),
    action          VARCHAR2(10)    NOT NULL,
    triggered_rules VARCHAR2(2000),
    rule_details    CLOB,
    evaluated_at    TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT pk_eval_results PRIMARY KEY (result_id)
);

CREATE INDEX idx_eval_client_time ON evaluation_results (client_id, evaluated_at);
CREATE INDEX idx_eval_action_time ON evaluation_results (action, evaluated_at);
CREATE INDEX idx_eval_time ON evaluation_results (evaluated_at);
```

### 4.8 REVIEW_QUEUE

```sql
CREATE TABLE review_queue (
    item_id         VARCHAR2(64)    NOT NULL,
    txn_id          VARCHAR2(64)    NOT NULL,
    client_id       VARCHAR2(32)    NOT NULL,
    composite_score NUMBER(6,2),
    action          VARCHAR2(10)    NOT NULL,
    status          VARCHAR2(20)    DEFAULT 'PENDING' NOT NULL,
    feedback        VARCHAR2(20),
    reviewed_by     VARCHAR2(64),
    reviewed_at     TIMESTAMP,
    auto_accept_at  TIMESTAMP,
    created_at      TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT pk_review_queue PRIMARY KEY (item_id)
);

CREATE INDEX idx_rq_status_created ON review_queue (status, created_at);
CREATE INDEX idx_rq_client ON review_queue (client_id, created_at);
```

### 4.9 ANOMALY_RULES

```sql
CREATE TABLE anomaly_rules (
    rule_id         VARCHAR2(64)    NOT NULL,
    rule_name       VARCHAR2(100)   NOT NULL,
    rule_type       VARCHAR2(50)    NOT NULL,
    description     VARCHAR2(500),
    enabled         NUMBER(1)       DEFAULT 1,
    variance_pct    NUMBER(6,2)     DEFAULT 200,
    risk_weight     NUMBER(4,2)     DEFAULT 1.0,
    version         NUMBER(8)       DEFAULT 0 NOT NULL,

    CONSTRAINT pk_anomaly_rules PRIMARY KEY (rule_id)
);
```

### 4.10 Supporting Tables

```sql
CREATE TABLE rule_weight_history (
    id              VARCHAR2(64)    NOT NULL,
    rule_id         VARCHAR2(64)    NOT NULL,
    old_weight      NUMBER(4,2),
    new_weight      NUMBER(4,2),
    reason          VARCHAR2(200),
    changed_at      TIMESTAMP       DEFAULT SYSTIMESTAMP,

    CONSTRAINT pk_weight_history PRIMARY KEY (id)
);
CREATE INDEX idx_wh_rule ON rule_weight_history (rule_id, changed_at);

CREATE TABLE ai_feedback (
    id              VARCHAR2(64)    NOT NULL,
    txn_id          VARCHAR2(64)    NOT NULL,
    rating          VARCHAR2(10)    NOT NULL,
    created_at      TIMESTAMP       DEFAULT SYSTIMESTAMP,

    CONSTRAINT pk_ai_feedback PRIMARY KEY (id)
);
CREATE INDEX idx_aif_txn ON ai_feedback (txn_id);
```

---

## 5. Index Summary — No Full Table Scans

| Query | Table | Index Used | Scan Type |
|-------|-------|------------|-----------|
| Get transaction by ID | `transactions` | PK | Unique |
| Client transaction history | `transactions` | `idx_txn_client_time` | Range (partition-pruned if EE) |
| Retention delete (SE2) | `transactions` | `idx_txn_created` | Range |
| Get profile by client | `client_profiles` | PK | Unique |
| Get type stats for client | `client_type_stats` | PK | Range prefix |
| Get seasonal stats for client | `client_seasonal_stats` | PK | Range prefix |
| Get beneficiaries for client | `beneficiary_stats` | PK | Range prefix |
| Find shared beneficiaries | `beneficiary_stats` | `idx_bene_reverse` | Range prefix |
| Stale beneficiary cleanup | `beneficiary_stats` | `idx_bene_last_seen` | Range |
| Get counters for client+type+bucket | `client_counters` | PK | Unique |
| Evaluation history by client | `evaluation_results` | `idx_eval_client_time` | Range (partition-pruned if EE) |
| Evaluations by action in time range | `evaluation_results` | `idx_eval_action_time` | Range (partition-pruned if EE) |
| Retention delete (SE2) | `evaluation_results` | `idx_eval_time` | Range |
| Pending review queue | `review_queue` | `idx_rq_status_created` | Range |
| Review queue by client | `review_queue` | `idx_rq_client` | Range prefix |
| Rule weight history | `rule_weight_history` | `idx_wh_rule` | Range prefix |

---

## 6. Kafka (MSK) Topic Design

### Topic: `txn-events`

| Property | Value | Rationale |
|----------|-------|-----------|
| Partitions | 24 | 3× max pods (8). Allows even distribution at any scale from 1 to 8 pods. |
| Replication factor | 3 | MSK default, standard durability |
| Retention | 7 days | Replay window for reprocessing / recovery |
| Key | `clientId` | All txns for one client go to the same partition → same pod |
| Value | Transaction JSON (same payload as POST body) |
| Compression | lz4 | Low CPU overhead, good compression for JSON |
| Cleanup policy | delete | Time-based retention, no compaction needed |

### MSK-Specific Configuration

```yaml
# MSK broker config (via MSK Configuration)
auto.create.topics.enable: false          # Create topic via IaC/CLI, not auto
default.replication.factor: 3
min.insync.replicas: 2                    # Allow 1 broker failure
log.retention.hours: 168                  # 7 days
```

### Why Kafka Solves the Multi-Instance Problem

- **Partition assignment = client ownership.** With 24 partitions and 8 pods, each pod owns 3 partitions. All transactions for clients hashing to those 3 partitions are processed by that single pod. No two pods ever write the same client's profile concurrently.
- **Rebalance on scale.** Scale from 4 pods to 8 → Kafka redistributes. Each pod goes from 6 partitions to 3. New owner loads profiles from Oracle on first cache miss.
- **Ordered processing per client.** Within a partition, messages are consumed in order. No out-of-order EWMA updates.
- **Replay for recovery.** If a consumer crashes mid-batch, uncommitted offsets cause Kafka to redeliver. Profile updates are idempotent (flushed as absolute values, not deltas).

### Consumer Configuration

```yaml
spring:
  kafka:
    bootstrap-servers: ${MSK_BOOTSTRAP_SERVERS}
    consumer:
      group-id: anomaly-detection
      auto-offset-reset: earliest
      enable-auto-commit: false           # Manual commit after batch flush
      max-poll-records: 500               # Process up to 500 per poll
      max-poll-interval-ms: 30000
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.bank.anomaly.model"
        partition.assignment.strategy: org.apache.kafka.clients.consumer.CooperativeStickyAssignor
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      acks: all
      retries: 3
      properties:
        enable.idempotence: true          # Exactly-once producer semantics
    properties:
      security.protocol: SSL             # MSK TLS
```

**`CooperativeStickyAssignor`** is critical: during scale-up/down, it only reassigns the minimum number of partitions. Non-affected partitions keep their assignment — no unnecessary cache eviction.

### Kafka Consumer Lag Monitoring (Prometheus + Grafana)

MSK exposes consumer lag metrics via JMX. Scrape via Prometheus JMX Exporter on each pod:

```yaml
# prometheus.yml
- job_name: 'kafka-consumer'
  metrics_path: /actuator/prometheus
  static_configs:
    - targets: ['app:8080']

# Key metrics to alert on:
# kafka_consumer_records_lag_max          > 5000 → consumer falling behind
# kafka_consumer_records_consumed_rate    → throughput per pod
# kafka_consumer_commit_latency_avg      → flush cycle health
```

Spring Boot + Micrometer auto-exports Kafka consumer metrics to `/actuator/prometheus` with `spring-kafka` + `micrometer-registry-prometheus` on the classpath. No additional JMX exporter needed.

**Grafana dashboard panel suggestions:**
- Consumer lag per partition (timeseries)
- Records consumed rate per pod (timeseries)
- Flush cycle duration p50/p99 (timeseries)
- Dirty profile count at flush time (gauge)

---

## 7. Write Path — Batch Accumulation

### The Problem with Per-Transaction Writes

At 200 TPS, writing to Oracle on every transaction means:
- 200 `UPDATE client_profiles` per second
- 200 × 5-7 counter types = 1,000-1,400 `MERGE client_counters` per second
- 200 `MERGE beneficiary_stats` per second

Total: ~1,600+ DML operations/sec. Achievable but generates unnecessary redo log volume.

### Solution: In-Memory Accumulation + Periodic Batch Flush

Each Kafka consumer thread maintains a local cache for its assigned clients. Flush every **5 seconds**:

```
┌─────────────────────────────────────────────────────────────┐
│ Kafka Consumer Thread (Pod 1, partitions 0-2)               │
│                                                             │
│  ┌─────────────────────────┐                                │
│  │ Local Profile Cache     │  HashMap<String, ProfileDTO>   │
│  │ (dirty-flagged)         │  Only clients in partitions    │
│  └─────────────────────────┘  0-2 are present (~25 clients) │
│                                                             │
│  ┌─────────────────────────┐                                │
│  │ Local Counter Cache     │  HashMap<CounterKey, long[]>   │
│  │ (accumulated deltas)    │  {count_delta, sum_delta}      │
│  └─────────────────────────┘                                │
│                                                             │
│  ┌─────────────────────────┐                                │
│  │ Local Bene Cache        │  HashMap<BeneKey, BeneDTO>     │
│  │ (dirty-flagged)         │                                │
│  └─────────────────────────┘                                │
│                                                             │
│  ┌─────────────────────────┐                                │
│  │ Flush Timer (every 5s)  │──→ Batch flush to Oracle RDS   │
│  └─────────────────────────┘                                │
└─────────────────────────────────────────────────────────────┘
```

### Flush Cycle Detail

```java
void flushDirtyState(Acknowledgment acknowledgment) {
    // 1. Batch UPDATE client_profiles (only dirty ones)
    //    With 200 clients across 8 pods, each pod owns ~25 clients
    //    At 200 TPS, ~25 profiles dirty per 5s cycle
    List<ClientProfile> dirty = cache.getDirtyProfiles();
    if (!dirty.isEmpty()) {
        jdbcTemplate.batchUpdate(
            "UPDATE client_profiles SET ewma_amount = ?, amount_m2 = ?, " +
            "total_txn_count = ?, ..., version = version + 1, " +
            "last_updated = SYSTIMESTAMP " +
            "WHERE client_id = ? AND version = ?",
            new BatchPreparedStatementSetter() { /* bind dirty profiles */ }
        );
    }

    // 2. Batch MERGE client_counters (accumulated deltas)
    //    100 txns for one client in 5s → 1 MERGE with delta=100
    Map<CounterKey, long[]> counterDeltas = cache.drainCounterDeltas();
    jdbcTemplate.batchUpdate(COUNTER_MERGE_SQL, /* bind deltas */);

    // 3. Batch MERGE beneficiary_stats
    jdbcTemplate.batchUpdate(BENE_MERGE_SQL, /* bind dirty benes */);

    // 4. Batch MERGE type stats + seasonal stats
    jdbcTemplate.batchUpdate(TYPE_STATS_MERGE_SQL, ...);
    jdbcTemplate.batchUpdate(SEASONAL_STATS_MERGE_SQL, ...);

    // 5. Commit Kafka offsets AFTER successful DB flush
    acknowledgment.acknowledge();
}
```

### Why This Is Safe

1. **Single-writer per client** — Kafka partitioning guarantees only one consumer thread writes a given `client_id`. No concurrent UPDATE on the same row. No deadlocks.
2. **Optimistic locking as a safety net** — During rebalance overlap, `WHERE version = ?` prevents stale overwrites. The pod with the stale version re-reads and retries.
3. **Kafka offset commit after DB commit** — If flush fails, offsets are not committed. Kafka redelivers on restart. Profile updates are idempotent (absolute values, not deltas).
4. **Counter deltas are accumulated** — 100 transactions for the same client in 5 seconds → 1 MERGE. Massive write reduction.

### Write Volume Comparison

| Metric | Aerospike (current) | Oracle (batch, 5s flush) |
|--------|---------------------|--------------------------|
| Profile writes/sec | 200 PUTs (168KB each) | ~25 rows in 1 batch/5s/pod |
| Counter writes/sec | 1,000-1,400 atomic ops | ~50-100 MERGEs in 1 batch/5s/pod |
| Beneficiary writes/sec | 200 (embedded in 168KB PUT) | ~50 MERGEs in 1 batch/5s/pod |
| Total DML/sec | ~1,600 (huge records) | **~35 batched ops/5s/pod** |
| Redo log pressure | 168KB × 200/sec = 33 MB/s | ~200 bytes × 125/5s = negligible |

---

## 8. Read Path — Dashboard and API Queries

All dashboard reads go directly to Oracle RDS. The consumer cache is write-only (accumulation), not a read-through cache for the API layer.

### Profile-Related Reads

```java
// Investigation page: load client profile — PK unique, <1ms
@Query("SELECT * FROM client_profiles WHERE client_id = :clientId")
Optional<ClientProfile> findByClientId(String clientId);

// Type distribution — PK prefix, 5 rows, <1ms
@Query("SELECT * FROM client_type_stats WHERE client_id = :clientId")
List<ClientTypeStats> findTypeStats(String clientId);

// Seasonal patterns — PK prefix, 24 or 7 rows, <1ms
@Query("SELECT * FROM client_seasonal_stats WHERE client_id = :clientId AND period_type = :periodType")
List<ClientSeasonalStats> findSeasonalStats(String clientId, String periodType);
```

### Beneficiary Network Graph

```sql
-- Step 1: Get all beneficiaries for selected clients (PK prefix scan)
SELECT client_id, beneficiary_id, txn_count, total_amount
FROM beneficiary_stats
WHERE client_id IN (:clientIds);

-- Step 2: Find shared beneficiaries (uses idx_bene_reverse)
SELECT b1.client_id AS client1, b2.client_id AS client2,
       b1.beneficiary_id, b1.txn_count AS count1, b2.txn_count AS count2
FROM beneficiary_stats b1
JOIN beneficiary_stats b2
  ON b1.beneficiary_id = b2.beneficiary_id
 AND b1.client_id < b2.client_id
WHERE b1.client_id IN (:clientIds)
  OR b2.client_id IN (:clientIds);
```

**Performance:** Index nested loops on `idx_bene_reverse`. For 10 clients × ~2,500 beneficiaries = ~25,000 index probes — sub-50ms. **No 168KB JSON deserialization. No full table scan.**

### Dashboard Aggregation Queries

```sql
-- Review queue stats (idx_rq_status_created)
SELECT status, COUNT(*) FROM review_queue
WHERE created_at > SYSTIMESTAMP - INTERVAL :hours HOUR
GROUP BY status;

-- Rule performance (idx_eval_action_time, partition-pruned if EE)
SELECT triggered_rules, action, COUNT(*)
FROM evaluation_results
WHERE evaluated_at BETWEEN :from AND :to
GROUP BY triggered_rules, action;

-- Evaluation trend over time
SELECT TRUNC(evaluated_at, 'HH24') AS hour, action, COUNT(*)
FROM evaluation_results
WHERE evaluated_at BETWEEN :from AND :to
GROUP BY TRUNC(evaluated_at, 'HH24'), action
ORDER BY hour;
```

---

## 9. Kafka Rebalance Handling

When pods are added/removed, Kafka reassigns partitions. Using `CooperativeStickyAssignor`, only the minimum set of partitions moves:

```java
@Component
public class TransactionConsumer {

    private final Map<String, ClientProfile> profileCache = new ConcurrentHashMap<>();
    private final Map<CounterKey, long[]> counterDeltas = new ConcurrentHashMap<>();
    private final Map<String, BeneficiaryStats> beneCache = new ConcurrentHashMap<>();

    @EventListener
    public void onPartitionsRevoked(ConsumerPartitionsRevokedEvent event) {
        // Flush all dirty state to Oracle BEFORE releasing partitions
        flushDirtyState();
        // Clear caches for revoked partitions
        Set<Integer> revoked = event.getPartitions().stream()
            .map(TopicPartition::partition).collect(toSet());
        profileCache.entrySet().removeIf(e ->
            revoked.contains(partitionFor(e.getKey())));
        counterDeltas.entrySet().removeIf(e ->
            revoked.contains(partitionFor(e.getKey().clientId())));
        beneCache.entrySet().removeIf(e ->
            revoked.contains(partitionFor(extractClientId(e.getKey()))));
    }

    @EventListener
    public void onPartitionsAssigned(ConsumerPartitionsAssignedEvent event) {
        // Profiles loaded lazily on first cache miss (SELECT from Oracle)
        log.info("Assigned partitions: {}", event.getPartitions());
    }

    private int partitionFor(String clientId) {
        return Utils.toPositive(Utils.murmur2(clientId.getBytes())) % 24;
    }
}
```

**Key guarantee:** Dirty state is always flushed before partitions are released. The new owner reads the latest committed state from Oracle.

---

## 10. Connection Pool Sizing (RDS)

```yaml
spring:
  datasource:
    url: jdbc:oracle:thin:@${RDS_ENDPOINT}:1521:ORCL
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    driver-class-name: oracle.jdbc.OracleDriver
    hikari:
      maximum-pool-size: 20              # Per pod
      minimum-idle: 5
      connection-timeout: 3000
      idle-timeout: 600000
      max-lifetime: 1800000
      leak-detection-threshold: 60000    # Log if connection held >60s
```

**Why 20 per pod is sufficient:**
- Batch flush: 1 connection × ~50ms every 5s = 1% utilization
- Dashboard reads: ~5-10 concurrent queries per pod
- Write-once inserts (eval results): batched in flush cycle
- **Total across 8 pods: 8 × 20 = 160 connections.** RDS `db.r5.xlarge` supports 500+ connections. Ample headroom.

---

## 11. Data Retention & Cleanup

### With Partitioning (EE): Instant Partition Drop

```sql
-- Run daily via Spring @Scheduled or AWS Lambda
-- Transactions: drop partitions older than 30 days
DECLARE
    v_cutoff TIMESTAMP := SYSTIMESTAMP - INTERVAL '30' DAY;
BEGIN
    FOR p IN (
        SELECT partition_name
        FROM user_tab_partitions
        WHERE table_name = 'TRANSACTIONS'
          AND partition_name != 'P_INITIAL'
    ) LOOP
        EXECUTE IMMEDIATE
            'SELECT COUNT(*) FROM transactions PARTITION (' || p.partition_name || ') WHERE ROWNUM = 1 AND created_at < :1'
            USING v_cutoff;
        -- If all rows in partition are older, drop it (instant DDL)
        EXECUTE IMMEDIATE 'ALTER TABLE transactions DROP PARTITION ' || p.partition_name;
    END LOOP;
END;

-- Same pattern for evaluation_results (30-day retention)
```

### Without Partitioning (SE2): Indexed Batched Delete

```sql
-- Run nightly, delete in batches to limit undo/redo pressure
DECLARE
    v_cutoff TIMESTAMP := SYSTIMESTAMP - INTERVAL '30' DAY;
    v_deleted NUMBER;
BEGIN
    LOOP
        DELETE FROM transactions
        WHERE created_at < v_cutoff
        AND ROWNUM <= 100000;                -- 1 lakh per batch
        v_deleted := SQL%ROWCOUNT;
        COMMIT;
        EXIT WHEN v_deleted = 0;
    END LOOP;
END;

-- Same for evaluation_results
```

### Common Cleanup (Both Editions)

```sql
-- Beneficiaries inactive > 90 days (uses idx_bene_last_seen)
DELETE FROM beneficiary_stats WHERE last_seen < SYSTIMESTAMP - INTERVAL '90' DAY;

-- Hourly counters older than 48 hours
DELETE FROM client_counters
WHERE counter_type LIKE 'HOURLY%'
AND bucket < TO_CHAR(SYSTIMESTAMP - INTERVAL '2' DAY, 'YYYYMMDDHH24');

-- Daily counters older than 90 days
DELETE FROM client_counters
WHERE counter_type LIKE 'DAILY%'
AND bucket < TO_CHAR(SYSTIMESTAMP - INTERVAL '90' DAY, 'YYYYMMDD');

-- Resolved review queue items older than 90 days
DELETE FROM review_queue
WHERE status IN ('TRUE_POSITIVE', 'FALSE_POSITIVE', 'AUTO_ACCEPTED')
AND reviewed_at < SYSTIMESTAMP - INTERVAL '90' DAY;
```

---

## 12. Deadlock Prevention Summary

| Risk | Mitigation |
|------|------------|
| Two pods UPDATE same `client_profiles` row | Kafka partitioning: single-writer per client. Optimistic locking (`WHERE version = ?`) as safety net during rebalance. |
| Batch UPDATE locks rows in different order across pods | Each pod only updates its own partition's clients (~25 clients). No overlap. |
| MERGE on `client_counters` from multiple pods | Same client's counters only touched by one pod (Kafka partitioning). |
| Dashboard read blocks consumer write | Oracle MVCC: readers never block writers, writers never block readers. |
| Long-running analytics query holds locks | All reads are consistent snapshots (Oracle default). No row locks acquired. |
| Cleanup job (DELETE) conflicts with writes | Cleanup targets data >30/90 days old. Active writes target current data. No overlap. |

---

## 13. Schema Migration (Liquibase)

```
src/main/resources/db/changelog/
├── db.changelog-master.yaml
├── changes/
│   ├── 001-create-transactions.yaml
│   ├── 002-create-client-profiles.yaml
│   ├── 003-create-client-type-stats.yaml
│   ├── 004-create-seasonal-stats.yaml
│   ├── 005-create-beneficiary-stats.yaml
│   ├── 006-create-client-counters.yaml
│   ├── 007-create-evaluation-results.yaml
│   ├── 008-create-review-queue.yaml
│   ├── 009-create-anomaly-rules.yaml
│   ├── 010-create-weight-history.yaml
│   ├── 011-create-ai-feedback.yaml
│   └── 012-seed-anomaly-rules.yaml
```

**db.changelog-master.yaml:**

```yaml
databaseChangeLog:
  - includeAll:
      path: changes/
      relativeToChangelogFile: true
```

**Example changeset (001-create-transactions.yaml):**

```yaml
databaseChangeLog:
  - changeSet:
      id: 001-create-transactions
      author: anomaly-detection-team
      changes:
        - sqlFile:
            path: sql/001-create-transactions.sql
            relativeToChangelogFile: true
      rollback:
        - dropTable:
            tableName: transactions
```

```yaml
# application.yml
spring:
  liquibase:
    enabled: true
    change-log: classpath:db/changelog/db.changelog-master.yaml
```

**Dependencies (`build.gradle.kts`):**

```kotlin
// Remove
implementation("com.aerospike:aerospike-client:7.x")

// Add
implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
implementation("org.springframework.kafka:spring-kafka")
implementation("com.oracle.database.jdbc:ojdbc11:23.4.0.24.05")
implementation("org.liquibase:liquibase-core")
```

---

## 14. Removed vs Retained from Current Codebase

### Removed

| Component | Reason |
|-----------|--------|
| All Aerospike dependencies (`aerospike-client`) | Replaced by Oracle RDS |
| `AerospikeConfig.java` | Replaced by `DataSourceConfig` + HikariCP |
| All `*Repository.java` files using Aerospike client (6 files) | Replaced by Spring Data JDBC + JdbcTemplate |
| Aerospike Docker service in `docker-compose.yml` | Replaced by RDS (or Oracle container for local dev) |
| `aerospike/` config directory | No longer needed |
| Synchronous evaluation in HTTP thread | Replaced by Kafka-based async pipeline |

### Retained (Unchanged Logic, New Data Layer)

| Component | Notes |
|-----------|-------|
| All 16 anomaly rules (`rules/` package) | Pure Java, no DB dependency |
| `RiskScoringService.java` | Pure computation, unchanged |
| `ProfileService.java` | EWMA/Welford math retained, reads from in-memory cache instead of Aerospike |
| Dashboard React app | Unchanged — same REST API contracts |
| Grafana dashboards | Unchanged — Micrometer/Prometheus metrics |
| MCP server | Unchanged — calls same REST APIs |
| Ollama/LLM integration | Unchanged |
| Twilio notifications | Unchanged |

---

## 15. File Change Inventory

### New Java Files (10)

| File | Purpose |
|------|---------|
| `config/DataSourceConfig.java` | HikariCP + Oracle RDS DataSource |
| `config/KafkaConfig.java` | MSK producer/consumer config, topic admin |
| `kafka/TransactionProducer.java` | Publishes transaction events to MSK |
| `kafka/TransactionConsumer.java` | Consumes events, local cache, batch flush, rebalance handling |
| `kafka/ConsumerCacheManager.java` | In-memory profile/counter/bene cache with dirty tracking |
| `repository/jdbc/ClientProfileJdbcRepository.java` | Spring Data JDBC for profiles |
| `repository/jdbc/TransactionJdbcRepository.java` | Spring Data JDBC for transactions |
| `repository/jdbc/EvaluationResultJdbcRepository.java` | Spring Data JDBC for results |
| `repository/jdbc/CounterJdbcRepository.java` | JdbcTemplate batch MERGE for counters |
| `scheduler/DataRetentionScheduler.java` | Nightly cleanup: stale benes, old counters, retention deletes/partition drops |

### Modified Java Files (5)

| File | Change |
|------|--------|
| `controller/TransactionController.java` | INSERT txn → publish to Kafka → return 202 |
| `service/TransactionEvaluationService.java` | Called by consumer, reads from local cache |
| `service/ProfileService.java` | Math unchanged. Data source: local cache |
| `model/ClientProfile.java` | Remove beneficiary maps. Add `version`, `dirty`. |
| `service/BeneficiaryGraphService.java` | Query Oracle (indexed join) instead of profile JSON |

### Removed Java Files (6+)

| File | Reason |
|------|--------|
| `config/AerospikeConfig.java` | Replaced |
| `repository/ClientProfileRepository.java` | Replaced by JDBC |
| `repository/TransactionRepository.java` | Replaced |
| `repository/RiskResultRepository.java` | Replaced |
| `repository/ReviewQueueRepository.java` | Replaced |
| `repository/AiFeedbackRepository.java` | Replaced |

### New Config/Resource Files

| File | Purpose |
|------|---------|
| `src/main/resources/db/changelog/**` | Liquibase changesets (12 changesets) |
| `src/main/resources/application-oracle.yml` | Oracle RDS + MSK Spring profile config |

---

## 16. Load Test Plan

### Profile: Warm Up → Sustain 200 TPS → Ramp Down

```
Phase 1 — Warm Up (2 minutes)
  0s:    0 TPS
  30s:   50 TPS
  60s:   100 TPS
  90s:   150 TPS
  120s:  200 TPS

Phase 2 — Sustained Load (10 minutes)
  200 TPS constant
  200 clients, random distribution
  Mixed transaction types (NEFT, UPI, RTGS, IMPS, WIRE)
  ~70% with beneficiary data

Phase 3 — Ramp Down (1 minute)
  200 TPS → 0 TPS linear decrease

Total transactions: ~1,32,000 (~1.3 lakh)
```

### Metrics to Capture

| Metric | Target | Alert If |
|--------|--------|----------|
| API p99 latency (POST /evaluate) | < 15ms | > 50ms |
| Kafka consumer lag | < 1,000 | > 5,000 |
| Flush cycle duration p99 | < 200ms | > 1,000ms |
| Oracle active sessions | < 10 | > 30 |
| HikariCP active connections | < 10/pod | > 15/pod |
| Oracle redo log rate | < 5 MB/s | > 20 MB/s |
| JVM heap usage | < 70% | > 85% |
| Oracle CPU utilization (RDS) | < 40% | > 70% |

### Tooling

- **Gatling or k6** for load generation (HTTP POST with randomized transaction payloads)
- **Prometheus + Grafana** for real-time dashboards during test
- **RDS Performance Insights** for Oracle-side analysis (top SQL, wait events)
- **MSK Console** for Kafka metrics (broker CPU, partition lag)

---

## 17. Verification Checklist

After implementation, verify:

1. **Schema deployed:** `SELECT table_name FROM user_tables` returns all 11 tables
2. **Liquibase applied:** `SELECT * FROM databasechangelog` shows all 12 changesets
3. **MSK topic created:** Verify `txn-events` topic with 24 partitions in MSK console
4. **API latency:** `POST /evaluate` returns 202 in < 15ms (measure p99)
5. **Consumer lag:** Prometheus `kafka_consumer_records_lag_max` < 1,000 at 200 TPS
6. **Profile accuracy:** Compare profile EWMA stats against manual calculation from transactions table
7. **Batch flush working:** Monitor `flush.duration` and `flush.rows` Micrometer metrics
8. **No full scans (EE):** `SELECT * FROM v$sql_plan WHERE operation = 'TABLE ACCESS' AND options = 'FULL'` — no matches from our app
9. **No full scans (SE2):** Review explain plans for all queries in `CounterJdbcRepository`, `TransactionJdbcRepository`, etc.
10. **Deadlock monitoring:** `SELECT * FROM v$lock WHERE block = 1` — empty during load test
11. **Rebalance test:** Kill one pod, verify other pods pick up its partitions, consumer lag recovers, profiles load from Oracle
12. **Retention test:** Run retention scheduler, verify old partitions dropped (EE) or rows deleted (SE2)

---

## 18. Decisions Log

All open questions resolved. Recorded here for traceability:

| # | Question | Decision | Impact |
|---|----------|----------|--------|
| 1 | Oracle hosting | **AWS RDS** | Connection pooling via HikariCP. Automated backups. Need to verify EE + Partitioning option vs SE2. |
| 2 | Kafka hosting | **Amazon MSK** | Managed brokers. TLS security. MSK-specific monitoring via CloudWatch + Prometheus. |
| 3 | Kafka partitions | **24** (3× max 8 pods) | Even distribution at any pod count from 1 to 8. |
| 4 | Flush interval | **5 seconds** | Dashboard data may be up to 5s stale — accepted. |
| 5 | Transaction retention | **30 days** | ~6 crore rows/month. No compliance requirement. |
| 6 | Beneficiary TTL | **90 days** | Inactive beneficiaries evicted nightly. |
| 7 | Eval result retention | **30 days** | ~6 crore rows/month. Same as transactions. |
| 8 | Schema migration | **Liquibase** | YAML changesets in `db/changelog/`. |
| 9 | Kafka lag monitoring | **Prometheus scraping → Grafana** | Micrometer auto-exports Kafka consumer metrics. |
| 10 | Load test profile | **Warm up → 200 TPS sustained → ramp down** | Gatling/k6 with Prometheus + RDS Performance Insights. |

---

## 19. Action Item: Confirm RDS Oracle Edition

**Before implementation, confirm with infra team:**

- If **Oracle EE + Partitioning** is available on RDS: Use interval-partitioned `transactions` and `evaluation_results`. Retention via instant `DROP PARTITION`.
- If **Oracle SE2** (no partitioning): Use non-partitioned tables with the additional `idx_txn_created` and `idx_eval_time` indexes. Retention via batched DELETE (1 lakh rows/batch, nightly).

Both paths are fully documented in this proposal. The schema DDL, retention logic, and index strategy differ — but the application code (repositories, flush logic, consumer) is identical either way. The Liquibase changesets should use a Liquibase precondition or context tag to apply the correct DDL based on edition.

At 6 crore rows/month with a 30-day window, Oracle SE2 handles this workload without partitioning. Partitioning is a cost-vs-ops-convenience trade-off, not a correctness requirement.
