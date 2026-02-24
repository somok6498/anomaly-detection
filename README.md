# Anomaly Detection System

Real-time behavioral anomaly detection for banking transactions using rule-based evaluation + Isolation Forest ML model, with EWMA-based client profiling, WhatsApp/SMS alerts, and full OpenTelemetry observability.

## Architecture

- **Spring Boot 3.2.5** (Java 17) REST API
- **Aerospike 7.1** in-memory NoSQL database (via Docker)
- **13 anomaly evaluators** — 12 rule-based + 1 ML (Isolation Forest)
- **EWMA** (Exponential Weighted Moving Average) + Welford's online variance for client behavioral profiling
- **Twilio** WhatsApp / SMS notifications on blocked transactions
- **OpenTelemetry** traces (Jaeger) + Micrometer metrics (Prometheus + Grafana)
- **Swagger UI** for interactive API exploration
- **Flutter Web** dashboard (pre-built, served as static assets)

### Evaluation Pipeline

```
POST /api/v1/transactions/evaluate
  │
  ├─ 1. Load/create client behavioral profile (EWMA stats)
  ├─ 2. Build evaluation context (hourly counters, beneficiary data)
  ├─ 3. Evaluate against all active anomaly rules (13 evaluators)
  ├─ 4. Compute weighted composite risk score (0–100)
  ├─ 5. Determine action: PASS (<30) · ALERT (30–70) · BLOCK (≥70)
  ├─ 6. Update client profile with new transaction data
  ├─ 7. Persist transaction + evaluation result
  └─ 8. Send WhatsApp/SMS alert (async, BLOCK only)
```

### Rule Types

| Rule | What it detects | Default Weight |
|------|-----------------|----------------|
| `TRANSACTION_TYPE_ANOMALY` | Rarely or never-used transaction types | 2.5 |
| `TPS_SPIKE` | Hourly transaction count spikes vs EWMA baseline | 2.0 |
| `AMOUNT_ANOMALY` | Unusually large single transactions | 3.0 |
| `HOURLY_AMOUNT_ANOMALY` | Hourly cumulative amount spikes | 1.5 |
| `AMOUNT_PER_TYPE_ANOMALY` | Unusual amounts for specific transaction types | 1.5 |
| `BENEFICIARY_RAPID_REPEAT` | ≥5 transactions to same beneficiary in 1 hour | 3.0 |
| `BENEFICIARY_CONCENTRATION` | Disproportionate volume to a single beneficiary | 2.0 |
| `BENEFICIARY_AMOUNT_REPETITION` | Repeated identical amounts to same beneficiary (threshold evasion) | 2.5 |
| `DAILY_CUMULATIVE_AMOUNT` | Low-and-slow drip structuring — daily total exceeds EWMA | 2.5 |
| `NEW_BENEFICIARY_VELOCITY` | Round-robin mule fan-out — too many new beneficiaries/day | 3.5 |
| `DORMANCY_REACTIVATION` | Sudden activity after extended inactivity (configurable threshold) | 3.0 |
| `CROSS_CHANNEL_BENEFICIARY_AMOUNT` | Same beneficiary receives large total across multiple txn types/day | 2.5 |
| `ISOLATION_FOREST` | ML-based multi-dimensional anomaly detection (8 features) | 2.0 |

**Composite Score** = Σ(triggered partial score × weight) / Σ(triggered weight), capped at 100.

## Prerequisites

- **Docker** and **Docker Compose**
- **Java 17+** (only for local development without Docker)

## Quick Start (Docker — recommended)

Everything runs in Docker. No Java or Gradle installation needed.

### 1. Configure environment

Create a `.env` file in the project root (already in `.gitignore`):

```bash
TWILIO_ACCOUNT_SID=your_account_sid
TWILIO_AUTH_TOKEN=your_auth_token
TWILIO_FROM_NUMBER=+14155238886
TWILIO_TO_NUMBER=+919830709527
TWILIO_ENABLED=true
TWILIO_CHANNEL=whatsapp
```

> For WhatsApp, use Twilio's sandbox number `+14155238886` and join the sandbox by sending "join \<keyword\>" to it first.

### 2. Build and seed data (first time only)

```bash
# Build the app image and start Aerospike + Jaeger
docker-compose up -d --build aerospike jaeger

# Wait for Aerospike to be healthy, then seed demo data
docker-compose run --rm -e SPRING_PROFILES_ACTIVE=seed app

# Wait for "Seeding complete" in the logs, then Ctrl+C
```

### 3. Start / Stop

```bash
# Start all containers (Aerospike + Jaeger + App + Prometheus + Grafana)
docker-compose up -d

# Stop (keeps data in Aerospike volume)
docker-compose stop

# Start again later
docker-compose start
```

### Alternative: All-in-one container

A single container with embedded Aerospike + auto-seeding (no Jaeger):

```bash
docker-compose up -d --build allinone
```

### 4. Explore

| URL | Description |
|-----|-------------|
| http://localhost:8080/swagger-ui.html | Swagger UI |
| http://localhost:8080/index.html | Flutter dashboard |
| http://localhost:8080/v3/api-docs | OpenAPI spec (JSON) |
| http://localhost:3333 | Grafana dashboard (admin/admin) |
| http://localhost:16686 | Jaeger tracing UI |
| http://localhost:9090 | Prometheus query UI |
| http://localhost:8080/actuator/health | Health check |

## Quick Start (Local development)

Requires **Java 17+** installed locally.

### 1. Start Aerospike and Jaeger

```bash
docker-compose up -d aerospike jaeger
```

### 2. Seed data and start the app

```bash
./gradlew bootRun --args='--spring.profiles.active=seed'
```

Wait for seeding to complete, then stop (`Ctrl+C`) and start normally:

```bash
./gradlew bootRun
```

The app starts on **http://localhost:8080**.

### 3. Build the Dashboard (optional)

Requires [Flutter SDK](https://docs.flutter.dev/get-started/install):

```bash
cd dashboard_ui
flutter build web
cp -r build/web/* ../src/main/resources/static/
```

Then restart the app.

## Example: Evaluate a Transaction

```bash
curl -s -X POST http://localhost:8080/api/v1/transactions/evaluate \
  -H "Content-Type: application/json" \
  -d '{
    "txnId": "TEST-001",
    "clientId": "CLIENT-001",
    "txnType": "NEFT",
    "amount": 90000,
    "timestamp": '$(date +%s000)',
    "beneficiaryAccount": "1234567890",
    "beneficiaryIfsc": "HDFC0001234"
  }' | python3 -m json.tool
```

### Sample Response

```json
{
  "txnId": "TEST-001",
  "clientId": "CLIENT-001",
  "compositeScore": 45.2,
  "riskLevel": "MEDIUM",
  "action": "ALERT",
  "ruleResults": [
    {
      "ruleName": "Amount Anomaly",
      "ruleType": "AMOUNT_ANOMALY",
      "triggered": true,
      "deviationPct": 120.5,
      "partialScore": 55.0,
      "reason": "Amount 90000.00 exceeds EWMA 40832.15 by 120.5%"
    }
  ]
}
```

## API Endpoints

### Transactions

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/transactions/evaluate` | Evaluate a transaction for anomalies |
| GET | `/api/v1/transactions/{txnId}` | Get transaction by ID |
| GET | `/api/v1/transactions/client/{clientId}?limit=50` | List client transactions |
| GET | `/api/v1/transactions/results/{txnId}` | Get evaluation result |
| GET | `/api/v1/transactions/results/client/{clientId}?limit=20` | List client evaluation results |

### Rules

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/rules` | List all rules |
| GET | `/api/v1/rules/{ruleId}` | Get specific rule |
| POST | `/api/v1/rules` | Create a rule |
| PUT | `/api/v1/rules/{ruleId}` | Update a rule |
| DELETE | `/api/v1/rules/{ruleId}` | Delete a rule |

### Profiles

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/profiles/{clientId}` | Get client behavioral profile (EWMA stats) |

### Isolation Forest Models

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/models/train/{clientId}?numTrees=100&sampleSize=256` | Train model for client |
| POST | `/api/v1/models/train?numTrees=100&sampleSize=256` | Train models for all clients |
| GET | `/api/v1/models/{clientId}` | Get model metadata |

### Silence Detection

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/silence` | Get currently silent clients |
| POST | `/api/v1/silence/check` | Trigger immediate silence scan |

### Actuator / Observability

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/actuator/health` | Health check |
| GET | `/actuator/metrics` | Micrometer metrics |
| GET | `/actuator/prometheus` | Prometheus scrape endpoint |

## Observability

### Tracing (Jaeger)

Traces are exported via OTLP HTTP to Jaeger. A single transaction evaluation produces a full span tree:

```
anomaly-detection: POST /api/v1/transactions/evaluate
  └── transaction.evaluate
       ├── profile.get_or_create
       ├── rules.evaluate_all
       │   ├── rule.evaluate.AMOUNT_ANOMALY
       │   ├── rule.evaluate.TPS_SPIKE
       │   ├── rule.evaluate.ISOLATION_FOREST
       │   └── ... (all active rules)
       ├── risk.score.compute
       ├── profile.update
       └── notification.send (async, BLOCK only)
```

View traces at **http://localhost:16686** → select service `anomaly-detection`.

### Metrics & Dashboards (Grafana + Prometheus)

Prometheus scrapes metrics from the app every 5 seconds. Grafana comes pre-provisioned with an **Anomaly Detection** dashboard at **http://localhost:3333**.

**Dashboard panels:**

| Panel | Type | What it shows |
|-------|------|---------------|
| Evaluations by Action | Donut chart | PASS / ALERT / BLOCK distribution |
| Evaluation Rate Over Time | Time series | Evaluations per minute by action |
| Avg Composite Score by Action | Gauge | Mean risk score per action type |
| Composite Score Over Time | Time series | Average and max scores over time |
| Total Rules Triggered by Type | Bar chart | Which rules fire most often |
| Rule Trigger Rate Over Time | Time series | Rule triggers per minute |
| Notifications Sent | Stat | WhatsApp/SMS success vs error counts |
| Notification Rate Over Time | Time series | Notification rate per minute |
| HTTP Request Rate | Time series | Total requests per second |
| HTTP Response Time (p95/p99) | Time series | Latency percentiles |
| JVM Heap Memory | Time series | Used / committed / max heap |

**Custom Prometheus metrics:**

| Metric | Type | Labels |
|--------|------|--------|
| `evaluation_count_total` | Counter | `action` (PASS/ALERT/BLOCK) |
| `evaluation_composite_score` | Distribution Summary | `action` |
| `rule_triggered_count_total` | Counter | `rule_type` |
| `notification_sent_count_total` | Counter | `channel`, `status` |

Grafana login: **admin / admin** (anonymous viewing also enabled).

## Notifications (Twilio)

When a transaction is **BLOCKED** (composite score ≥ 70), an async WhatsApp or SMS alert is sent via Twilio containing:

- Client ID and Transaction ID
- Transaction amount
- Composite risk score
- Top triggering rule

Configure via environment variables (see `.env` setup above). Set `TWILIO_ENABLED=false` to disable.

## Silence Detection

A background scheduler proactively detects when normally-active clients **stop transacting** — the reverse of anomaly detection. This catches network failures, system outages, or account issues before the client complains.

### How It Works

Every `N` minutes (default: 5), the scheduler:
1. Scans all client profiles from Aerospike
2. For each client with sufficient history (≥48 hours of data, ≥1 txn/hour baseline):
   - Computes the **expected gap** between transactions from their EWMA hourly TPS
   - If the actual silence exceeds `silenceMultiplier × expectedGap` (default 3x), flags the client
3. Sends a WhatsApp/SMS alert for newly-detected silent clients
4. Avoids alert fatigue: no re-alerting until the client resumes transacting

### Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `risk.silence-detection.enabled` | true | Enable/disable the scheduler |
| `risk.silence-detection.check-interval-minutes` | 5 | How often to scan profiles |
| `risk.silence-detection.silence-multiplier` | 3.0 | Alert if gap > N× expected gap |
| `risk.silence-detection.min-expected-tps` | 1.0 | Skip clients with < N txn/hour |
| `risk.silence-detection.min-completed-hours` | 48 | Minimum hourly data before monitoring |

### API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/silence` | Get currently silent clients with duration |
| POST | `/api/v1/silence/check` | Trigger an immediate silence scan |

### Grafana Dashboard

The "Silence Detection" row in the Grafana dashboard shows:
- **Silent Clients (Current)** — gauge of currently-silent client count
- **Silence Detected Events** — timeline of new silence alerts per client
- **Silence Resolved Events** — timeline of clients resuming transactions

## Project Structure

```
src/main/java/com/bank/anomaly/
├── config/               # Aerospike, OpenAPI, Twilio, Metrics, Observation configs
├── controller/           # REST controllers (Transactions, Rules, Profiles, Models)
├── engine/
│   ├── evaluators/       # 13 rule evaluators (12 rule-based + 1 Isolation Forest)
│   └── isolationforest/  # Pure Java IF implementation (tree, node, feature extractor)
├── model/                # Domain models (Transaction, ClientProfile, AnomalyRule, etc.)
├── repository/           # Aerospike data access (5 repositories)
├── seeder/               # Demo data seeder & profile builder
└── service/              # Business logic (evaluation, scoring, profiling, notifications)

dashboard_ui/             # Flutter Web dashboard
aerospike/                # Aerospike server configuration
prometheus/               # Prometheus scrape configuration
grafana/provisioning/     # Grafana datasources + pre-built dashboard
```

## Seeded Test Data

Run with `SPRING_PROFILES_ACTIVE=seed` to generate ~72,000 transactions across 30 days:

| Clients | Behavior |
|---------|----------|
| CLIENT-001 to CLIENT-005 | Normal behavioral profiles (consistent patterns) |
| CLIENT-006 to CLIENT-010 | Normal base + injected anomalies in last 2 days |
| CLIENT-011 | Dormant account — 28 days active, then 2+ day gap with reactivation |

**Injected anomalies** (clients 006–010): unusual transaction types, spiked amounts (5–10x normal), TPS bursts (50 txns/hour), structuring patterns (15–25 rapid transfers to same beneficiary), drip structuring (40–60 small txns/day), new-beneficiary fan-out (8–12 new beneficiaries/day), and cross-channel splitting (same beneficiary via multiple txn types).

Each client has 20–50 unique beneficiaries with power-law distribution.

## Configuration

Key settings in `application.yml` (overridable via environment variables):

| Property | Default | Description |
|----------|---------|-------------|
| `risk.alert-threshold` | 30.0 | Score threshold for ALERT action |
| `risk.block-threshold` | 70.0 | Score threshold for BLOCK action |
| `risk.ewma-alpha` | 0.01 | EWMA smoothing factor (lower = slower adaptation) |
| `risk.min-profile-txns` | 20 | Grace period before rules apply to new clients |
| `risk.rule-cache-refresh-seconds` | 60 | Rule reload interval from Aerospike |
| `risk.transaction-types` | NEFT, RTGS, IMPS, UPI, IFT | Accepted transaction types (extensible — add CBDC etc.) |

### Rule Defaults

All rule parameters are configurable via `risk.rule-defaults.*` in `application.yml`. These serve as fallbacks when a rule doesn't specify parameters in its DB record. Priority: `rule.params > rule.variancePct > config defaults`.

| Property | Default | Used by |
|----------|---------|---------|
| `risk.rule-defaults.amount-anomaly-variance-pct` | 100.0 | Amount Anomaly |
| `risk.rule-defaults.tps-spike-variance-pct` | 50.0 | TPS Spike |
| `risk.rule-defaults.hourly-amount-variance-pct` | 80.0 | Hourly Amount |
| `risk.rule-defaults.amount-per-type-variance-pct` | 150.0 | Amount Per Type |
| `risk.rule-defaults.min-type-frequency-pct` | 5.0 | Transaction Type |
| `risk.rule-defaults.min-repeat-count` | 5 | Beneficiary Rapid Repeat |
| `risk.rule-defaults.beneficiary-concentration-variance-pct` | 200.0 | Beneficiary Concentration |
| `risk.rule-defaults.max-cv-pct` | 10.0 | Beneficiary Amount Repetition |
| `risk.rule-defaults.isolation-forest-threshold` | 60.0 | Isolation Forest |
| `risk.rule-defaults.daily-cumulative-variance-pct` | 150.0 | Daily Cumulative Amount |
| `risk.rule-defaults.daily-cumulative-min-days` | 3 | Daily Cumulative Amount |
| `risk.rule-defaults.new-bene-max-per-day` | 5 | New Beneficiary Velocity |
| `risk.rule-defaults.new-bene-variance-pct` | 200.0 | New Beneficiary Velocity |
| `risk.rule-defaults.dormancy-days` | 30 | Dormancy Reactivation |
| `risk.rule-defaults.cross-channel-bene-variance-pct` | 150.0 | Cross-Channel Bene Amount |

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Java 17 |
| Framework | Spring Boot 3.2.5 |
| Build | Gradle 8.7 |
| Database | Aerospike 7.1 (Docker) |
| ML | Isolation Forest (pure Java) |
| Notifications | Twilio SDK 10.1.0 (WhatsApp / SMS) |
| Tracing | Micrometer + OpenTelemetry → Jaeger |
| Metrics | Micrometer + Prometheus + Grafana |
| API Docs | springdoc-openapi (Swagger UI) |
| Dashboard | Flutter Web |
| Containerization | Docker, Docker Compose |
