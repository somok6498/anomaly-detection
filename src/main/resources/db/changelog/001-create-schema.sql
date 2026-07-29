--liquibase formatted sql

--changeset anomaly:001-transactions
CREATE TABLE transactions (
    txn_id          VARCHAR2(64)    NOT NULL,
    client_id       VARCHAR2(32)    NOT NULL,
    amount          NUMBER(15,2)    NOT NULL,
    txn_type        VARCHAR2(20)    NOT NULL,
    beneficiary_account VARCHAR2(128),
    beneficiary_ifsc    VARCHAR2(32),
    timestamp_ms    NUMBER(15)      NOT NULL,
    created_at      TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_transactions PRIMARY KEY (txn_id)
);
CREATE INDEX idx_txn_client_time ON transactions (client_id, timestamp_ms);
CREATE INDEX idx_txn_created ON transactions (created_at);

--changeset anomaly:002-client-profiles
CREATE TABLE client_profiles (
    client_id               VARCHAR2(32)    NOT NULL,
    total_txn_count         NUMBER(12)      DEFAULT 0,
    ewma_amount             NUMBER(15,4)    DEFAULT 0,
    amount_m2               NUMBER(20,6)    DEFAULT 0,
    ewma_hourly_tps         NUMBER(12,4)    DEFAULT 0,
    tps_m2                  NUMBER(16,6)    DEFAULT 0,
    completed_hours_count   NUMBER(8)       DEFAULT 0,
    ewma_hourly_amount      NUMBER(15,4)    DEFAULT 0,
    hourly_amount_m2        NUMBER(20,6)    DEFAULT 0,
    ewma_daily_amount       NUMBER(15,4)    DEFAULT 0,
    daily_amount_m2         NUMBER(20,6)    DEFAULT 0,
    completed_days_count    NUMBER(8)       DEFAULT 0,
    ewma_daily_new_bene     NUMBER(12,4)    DEFAULT 0,
    daily_new_bene_m2       NUMBER(16,6)    DEFAULT 0,
    completed_days_bene_cnt NUMBER(8)       DEFAULT 0,
    distinct_bene_count     NUMBER(8)       DEFAULT 0,
    last_hour_bucket        VARCHAR2(12),
    last_day_bucket         VARCHAR2(10),
    last_updated            NUMBER(15)      DEFAULT 0,
    version                 NUMBER(8)       DEFAULT 0 NOT NULL,
    created_at              TIMESTAMP       DEFAULT SYSTIMESTAMP,
    CONSTRAINT pk_client_profiles PRIMARY KEY (client_id)
);

--changeset anomaly:003-client-type-stats
CREATE TABLE client_type_stats (
    client_id       VARCHAR2(32)    NOT NULL,
    txn_type        VARCHAR2(20)    NOT NULL,
    txn_count       NUMBER(12)      DEFAULT 0,
    avg_amount      NUMBER(15,4)    DEFAULT 0,
    amount_m2       NUMBER(20,6)    DEFAULT 0,
    amount_count    NUMBER(12)      DEFAULT 0,
    CONSTRAINT pk_client_type_stats PRIMARY KEY (client_id, txn_type)
);

--changeset anomaly:004-client-seasonal-stats
CREATE TABLE client_seasonal_stats (
    client_id       VARCHAR2(32)    NOT NULL,
    period_type     VARCHAR2(6)     NOT NULL,
    slot            VARCHAR2(4)     NOT NULL,
    tps_mean        NUMBER(12,4)    DEFAULT 0,
    tps_m2          NUMBER(16,6)    DEFAULT 0,
    tps_count       NUMBER(8)       DEFAULT 0,
    amt_mean        NUMBER(15,4)    DEFAULT 0,
    amt_m2          NUMBER(20,6)    DEFAULT 0,
    amt_count       NUMBER(8)       DEFAULT 0,
    CONSTRAINT pk_seasonal_stats PRIMARY KEY (client_id, period_type, slot)
);

--changeset anomaly:005-beneficiary-stats
CREATE TABLE beneficiary_stats (
    client_id       VARCHAR2(32)    NOT NULL,
    beneficiary_id  VARCHAR2(128)   NOT NULL,
    txn_count       NUMBER(8)       DEFAULT 0,
    total_amount    NUMBER(15,2)    DEFAULT 0,
    ewma_amount     NUMBER(15,4)    DEFAULT 0,
    amount_m2       NUMBER(20,6)    DEFAULT 0,
    last_seen       TIMESTAMP       DEFAULT SYSTIMESTAMP,
    CONSTRAINT pk_bene_stats PRIMARY KEY (client_id, beneficiary_id)
);
CREATE INDEX idx_bene_reverse ON beneficiary_stats (beneficiary_id, client_id);
CREATE INDEX idx_bene_last_seen ON beneficiary_stats (last_seen);

--changeset anomaly:006-client-counters
CREATE TABLE client_counters (
    client_id       VARCHAR2(32)    NOT NULL,
    counter_type    VARCHAR2(20)    NOT NULL,
    bucket          VARCHAR2(64)    NOT NULL,
    count_val       NUMBER(12)      DEFAULT 0,
    sum_val         NUMBER(15,2)    DEFAULT 0,
    CONSTRAINT pk_client_counters PRIMARY KEY (client_id, counter_type, bucket)
);

--changeset anomaly:007-evaluation-results
CREATE TABLE evaluation_results (
    txn_id          VARCHAR2(64)    NOT NULL,
    client_id       VARCHAR2(32)    NOT NULL,
    composite_score NUMBER(6,2),
    risk_level      VARCHAR2(20),
    action          VARCHAR2(10)    NOT NULL,
    rule_results    CLOB,
    evaluated_at    NUMBER(15)      NOT NULL,
    triggered_rule_count NUMBER(4)  DEFAULT 0,
    breadth_bonus   NUMBER(8,4)     DEFAULT 0,
    ai_explanation  CLOB,
    attack_pattern  VARCHAR2(2000),
    CONSTRAINT pk_eval_results PRIMARY KEY (txn_id)
);
CREATE INDEX idx_eval_client_time ON evaluation_results (client_id, evaluated_at);
CREATE INDEX idx_eval_action_time ON evaluation_results (action, evaluated_at);
CREATE INDEX idx_eval_time ON evaluation_results (evaluated_at);

--changeset anomaly:008-review-queue
CREATE TABLE review_queue (
    txn_id          VARCHAR2(64)    NOT NULL,
    client_id       VARCHAR2(32)    NOT NULL,
    action          VARCHAR2(10)    NOT NULL,
    composite_score NUMBER(6,2),
    risk_level      VARCHAR2(20),
    triggered_rule_ids VARCHAR2(2000),
    enqueued_at     NUMBER(15)      NOT NULL,
    feedback_status VARCHAR2(20)    DEFAULT 'PENDING' NOT NULL,
    feedback_at     NUMBER(15)      DEFAULT 0,
    feedback_by     VARCHAR2(64),
    auto_accept_deadline NUMBER(15) DEFAULT 0,
    CONSTRAINT pk_review_queue PRIMARY KEY (txn_id)
);
CREATE INDEX idx_rq_status_enqueued ON review_queue (feedback_status, enqueued_at);
CREATE INDEX idx_rq_client ON review_queue (client_id, enqueued_at);

--changeset anomaly:009-anomaly-rules
CREATE TABLE anomaly_rules (
    rule_id         VARCHAR2(64)    NOT NULL,
    rule_name       VARCHAR2(100)   NOT NULL,
    rule_type       VARCHAR2(50)    NOT NULL,
    description     VARCHAR2(500),
    enabled         NUMBER(1)       DEFAULT 1,
    variance_pct    NUMBER(6,2)     DEFAULT 200,
    risk_weight     NUMBER(4,2)     DEFAULT 1.0,
    params          CLOB,
    version         NUMBER(8)       DEFAULT 0 NOT NULL,
    CONSTRAINT pk_anomaly_rules PRIMARY KEY (rule_id)
);

--changeset anomaly:010-rule-weight-history
CREATE TABLE rule_weight_history (
    id              VARCHAR2(64)    NOT NULL,
    rule_id         VARCHAR2(64)    NOT NULL,
    old_weight      NUMBER(4,2),
    new_weight      NUMBER(4,2),
    tp_count        NUMBER(8)       DEFAULT 0,
    fp_count        NUMBER(8)       DEFAULT 0,
    tp_fp_ratio     NUMBER(8,4)     DEFAULT 0,
    adjusted_at     NUMBER(15)      NOT NULL,
    CONSTRAINT pk_weight_history PRIMARY KEY (id)
);
CREATE INDEX idx_wh_rule ON rule_weight_history (rule_id, adjusted_at);

--changeset anomaly:011-ai-feedback
CREATE TABLE ai_feedback (
    txn_id          VARCHAR2(64)    NOT NULL,
    helpful         NUMBER(1)       NOT NULL,
    operator_id     VARCHAR2(64),
    feedback_ts     NUMBER(15)      NOT NULL,
    CONSTRAINT pk_ai_feedback PRIMARY KEY (txn_id)
);

--changeset anomaly:012-isolation-forest-models
CREATE TABLE isolation_forest_models (
    client_id       VARCHAR2(32)    NOT NULL,
    model_json      CLOB,
    feature_count   NUMBER(4)       DEFAULT 6,
    tree_count      NUMBER(4),
    train_samples   NUMBER(8),
    trained_at      NUMBER(15),
    CONSTRAINT pk_if_models PRIMARY KEY (client_id)
);

--changeset anomaly:013-metrics-buckets
CREATE TABLE metrics_buckets (
    scope           VARCHAR2(64)    NOT NULL,
    metric          VARCHAR2(64)    NOT NULL,
    bucket          VARCHAR2(16)    NOT NULL,
    granularity     VARCHAR2(10)    NOT NULL,
    count_val       NUMBER(12)      DEFAULT 0,
    sum_val         NUMBER(15)      DEFAULT 0,
    max_val         NUMBER(15)      DEFAULT 0,
    min_val         NUMBER(15)      DEFAULT 0,
    CONSTRAINT pk_metrics_buckets PRIMARY KEY (scope, metric, bucket, granularity)
);
