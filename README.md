# Anomaly Detection System

Real-time behavioral anomaly detection for banking transactions using rule-based evaluation + Isolation Forest ML model, with EWMA-based client profiling, ops review queue with feedback-driven rule auto-tuning, WhatsApp/SMS alerts, and full OpenTelemetry observability.

## Architecture

- **Spring Boot 3.2.5** (Java 17) REST API
- **Aerospike 7.1** in-memory NoSQL database (via Docker)
- **16 anomaly evaluators** — 15 rule-based + 1 ML (Isolation Forest)
- **EWMA** (Exponential Weighted Moving Average) + Welford's online variance for client behavioral profiling
- **Ollama LLM** (llama3.2:1b) — AI chatbot for natural language queries, on-demand AI explanations for flagged transactions (with feedback-aware prompting), client risk profile narratives, smart alert triage, and attack pattern classification
- **Twilio** WhatsApp / SMS notifications on blocked transactions
- **OpenTelemetry** traces (Jaeger) + Micrometer metrics (Prometheus + Grafana)
- **Swagger UI** for interactive API exploration
- **Postman Collection** included (`Anomaly_Detection_API.postman_collection.json`) with all endpoints
- **Flutter Web** dashboard (pre-built, served as static assets)

### Evaluation Pipeline

```
POST /api/v1/transactions/evaluate
  │
  ├─ 1. Load/create client behavioral profile (EWMA stats)
  ├─ 2. Build evaluation context (hourly counters, beneficiary data)
  ├─ 3. Evaluate against all active anomaly rules (16 evaluators)
  ├─ 4. Compute weighted composite risk score (0–100)
  ├─ 5. Determine action: PASS (<30) · ALERT (30–70) · BLOCK (≥70)
  ├─ 6. Update client profile with new transaction data
  ├─ 7. Persist transaction + evaluation result
  ├─ 8. Enqueue ALERT/BLOCK transactions for ops review
  └─ 9. Send WhatsApp/SMS alert (async, BLOCK only)
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
| `SEASONAL_DEVIATION` | Time-slot-aware deviation — compares against hour-of-day/day-of-week baselines | 2.0 |
| `MULE_NETWORK` | Graph-based mule network detection via shared beneficiaries, fan-in convergence, and cluster density | 4.0 |
| `TEMPORAL_RULE_CORRELATION` | Meta-rule: detects repeated rule triggers for same client within configurable time window | 3.0 |
| `ISOLATION_FOREST` | ML-based multi-dimensional anomaly detection (8 features) | 2.0 |

**Composite Score** = Σ(triggered partial score × weight) / Σ(triggered weight) × breadth multiplier, capped at 100.

## Prerequisites

- **Docker** and **Docker Compose**
- **Java 17+** (only for local development without Docker)
- **Ollama** (optional, for AI chatbot + transaction explanations) — install natively for Metal GPU acceleration on macOS:
  ```bash
  # Install Ollama (macOS)
  brew install ollama

  # Start the Ollama server
  ollama serve

  # Pull the model (in another terminal)
  ollama pull llama3.2:1b
  ```
  The Docker app connects to native Ollama via `host.docker.internal:11434`.

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
  ],
  "aiExplanation": "This NEFT transaction of ₹90,000 by CLIENT-001 was flagged because the amount is 2.2x higher than the client's usual average of ₹40,832. The system detected this as a potential anomaly with a risk score of 45.2 out of 100, placing it in the ALERT category for ops review."
}
```

> **Note:** The `aiExplanation` field is generated on-demand by the Ollama LLM when the evaluation result is first viewed via `GET /api/v1/transactions/results/{txnId}` or the review queue detail endpoint. It is cached in Aerospike after generation. If Ollama is unavailable, the field will be `null`.

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

### Beneficiary Graph (Mule Network)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/graph/status` | Graph metadata: total beneficiaries, clients, last refresh, isReady |
| GET | `/api/v1/graph/beneficiary/{ifsc}/{account}` | Fan-in count and sender list for a beneficiary |
| GET | `/api/v1/graph/client/{clientId}` | Shared beneficiary count, ratio, and network density |

### Analytics

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/analytics/rules/performance?fromDate=&toDate=` | Per-rule TP/FP counts and precision from feedback (optional epoch ms time range) |
| GET | `/api/v1/analytics/ai-feedback/stats` | AI explanation feedback stats `{helpful, notHelpful, total, helpfulPct}` |
| GET | `/api/v1/analytics/graph/client/{clientId}/network` | Network graph nodes + edges for visualization |
| GET | `/api/v1/analytics/client/{clientId}/narrative` | AI-generated plain-English client risk narrative (LLM) |

### Silence Detection

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/silence` | Get currently silent clients (enriched: includes EWMA TPS, expected gap, silence duration, last txn time) |
| POST | `/api/v1/silence/check` | Trigger immediate silence scan |

### AI Chat

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/chat` | Send a natural language query. Body: `{"message": "..."}`. Returns summary, tabular data (columns + rows), and query type. |

### Demo Data

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/demo/generate` | Generate demo transactions and profiles on-the-fly (body: `{clientCount, txnsPerClient, anomalyPct}`) |

### Configuration

All configuration endpoints are live-reloadable — changes take effect immediately without restart.

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/config/thresholds` | Get alert/block thresholds and EWMA settings |
| PUT | `/api/v1/config/thresholds` | Update thresholds (alertThreshold, blockThreshold, ewmaAlpha, minProfileTxns) |
| GET | `/api/v1/config/feedback` | Get feedback loop / auto-tuning settings |
| PUT | `/api/v1/config/feedback` | Update feedback settings (timeout, tuning interval, weight floor/ceiling) |
| GET | `/api/v1/config/transaction-types` | Get accepted transaction types |
| PUT | `/api/v1/config/transaction-types` | Update accepted transaction types |
| GET | `/api/v1/config/silence` | Get silence detection settings |
| PUT | `/api/v1/config/silence` | Update silence detection settings (enabled, interval, multiplier, thresholds) |
| GET | `/api/v1/config/aerospike` | Get Aerospike connection info (read-only) |

### Actuator / Observability

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/actuator/health` | Health check |
| GET | `/actuator/metrics` | Micrometer metrics |
| GET | `/actuator/prometheus` | Prometheus scrape endpoint |

## Review Queue & Feedback Loop

ALERT and BLOCK transactions are automatically enqueued for ops review. Operators can mark items as **True Positive** (confirmed threat) or **False Positive** (dismissed), and the system uses this feedback to auto-tune rule weights over time.

### How It Works

```
Transaction evaluated as ALERT/BLOCK
  │
  ├─ 1. Enqueue to review_queue (status: PENDING)
  ├─ 2. Ops reviews via Flutter dashboard or API
  │     ├─ Mark as TRUE_POSITIVE  → confirmed anomaly
  │     └─ Mark as FALSE_POSITIVE → dismissed
  ├─ 3. If no action within timeout (default 1 hour) → AUTO_ACCEPTED
  └─ 4. Every 6 hours: auto-tuning job adjusts rule weights based on TP/FP ratio
```

### Auto-Accept Timeout

A scheduled job runs every 60 seconds and marks PENDING items past their deadline as `AUTO_ACCEPTED`. These are excluded from tuning calculations to prevent data pollution from unreviewed items.

### Rule Auto-Tuning Algorithm

Every 6 hours (configurable), the tuning job:
1. Fetches all items with explicit feedback (TRUE_POSITIVE or FALSE_POSITIVE only)
2. Computes per-rule TP/FP counts based on which rules triggered for each reviewed item
3. For rules with ≥50 samples: adjusts weight based on TP ratio with guardrails
   - High TP ratio → weight increases (rule is useful)
   - Low TP ratio → weight decreases (rule generates false positives)
   - Max adjustment: ±10% per cycle, weight clamped to [0.5, 5.0]

### Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `risk.feedback.auto-accept-timeout-ms` | 3600000 | Auto-accept timeout (1 hour) |
| `risk.feedback.auto-accept-check-interval-seconds` | 60 | How often to check for expired items |
| `risk.feedback.tuning-interval-hours` | 6 | How often to run rule auto-tuning |
| `risk.feedback.min-samples-for-tuning` | 50 | Minimum feedback samples before adjusting a rule |
| `risk.feedback.weight-floor` | 0.5 | Minimum allowed rule weight |
| `risk.feedback.weight-ceiling` | 5.0 | Maximum allowed rule weight |
| `risk.feedback.max-adjustment-pct` | 0.10 | Max weight change per tuning cycle (10%) |

### API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/review/queue` | List queue items (filters: action, clientId, fromDate, toDate, ruleId, limit) |
| GET | `/api/v1/review/queue/{txnId}` | Get full detail (queue item + evaluation + transaction + client profile) |
| POST | `/api/v1/review/queue/{txnId}/feedback` | Submit feedback `{status, feedbackBy}` |
| POST | `/api/v1/review/queue/{txnId}/ai-feedback` | Rate AI explanation `{helpful, operatorId}` |
| GET | `/api/v1/review/queue/{txnId}/ai-feedback` | Get existing AI explanation feedback |
| POST | `/api/v1/review/queue/bulk-feedback` | Bulk feedback `{txnIds[], status, feedbackBy}` |
| GET | `/api/v1/review/stats` | Queue stats `{pending, truePositive, falsePositive, autoAccepted}` |
| GET | `/api/v1/review/queue/triage` | Smart Alert Triage — LLM ranks pending items by urgency with reasoning |
| GET | `/api/v1/review/weight-history` | Rule weight change history (filters: ruleId, limit) |

### Flutter Dashboard

The dashboard has four tabs plus a floating AI assistant:

1. **Investigation** — Search by client or transaction ID. Client view shows profile stats, risk score trend chart (fl_chart line chart with PASS/ALERT/BLOCK color zones), transaction type distribution, average amount by type, transaction history, and evaluation history. Transaction detail view includes **AI Analysis** — an LLM-generated plain-English explanation of why the transaction was flagged (generated on-demand, cached in Aerospike). Includes CSV/PDF export buttons.

2. **Review Queue** — Two-panel layout with **time range selector** (1m/5m/15m/30m/1h/6h/12h/24h/7d presets + custom absolute date-time picker, default 15m), filter bar (action/status/client ID), score threshold filter (> or < operator), stats row (Pending/TP/FP/Auto-Accepted counts), **Smart Triage button** (LLM ranks pending alerts by urgency with reasoning — shows ranked list with CRITICAL/HIGH/MEDIUM/LOW badges), bulk action bar with select-all, sortable queue table, auto-accept countdown timers, and CSV/PDF export (includes REVIEWED AT column). Right panel shows full transaction detail with **AI Analysis card** featuring **attack pattern badge** (color-coded LLM classification — e.g., MULE_ACCOUNT, VELOCITY_ABUSE, STRUCTURING — with confidence and summary), LLM-generated explanation with thumbs up/down feedback, rule breakdown, client profile summary with **AI Narrative button** (generates LLM-powered behavioral summary), and weight history.

3. **Analytics** — **Time range selector** (same presets as Review Queue) for rule performance analytics. Includes precision bar chart + TP/FP breakdown table for all 16 rules based on review queue feedback. Each rule has an **(i) info tooltip** showing its description on hover. **AI Feedback Stats** card showing total ratings, helpful/not-helpful counts, helpful rate percentage with color-coded distribution bar. Beneficiary network visualization (force-directed graph showing client-beneficiary relationships, shared beneficiaries, and mule network topology with pan/zoom support). **Silence detection panel** showing currently silent clients with EWMA TPS, expected gap, actual silence duration, and last transaction time. Includes CSV/PDF export for rule performance data.

4. **Settings** — Live configuration management for all system parameters: alert/block thresholds, EWMA settings, feedback loop tuning (auto-accept timeout, tuning interval, weight bounds), accepted transaction types, silence detection settings (enabled toggle, check interval, silence multiplier, min TPS, min completed hours), and Aerospike connection info (read-only). Each rule has an **(i) info tooltip** showing its description on hover. Changes take effect immediately.

5. **AI Assistant** (floating) — Opens as a **slide-in side panel** (35% width, 360–520px clamped) from the bottom-right FAB button. Supports **maximize to fullscreen** and minimize back to side panel. Natural language query interface powered by Ollama (llama3.2:1b) with keyword-based fast parsing as primary and LLM as fallback. Supports queries like:
   - "How many clients did UPI in last 15 mins?"
   - "List transactions blocked in last 30 mins"
   - "Clients with shared beneficiaries in last 24 hours" (mule network detection)
   - "Silenced clients in the system" (EWMA-based silence detection)
   - "Show review queue stats"
   - Tabular results with CSV export

### AI-Powered Transaction Explanations

When an operator views a flagged transaction (in Review Queue detail or Investigation page), the system generates a **human-readable explanation** using the Ollama LLM. The explanation:

- States what happened (transaction type, amount, client)
- Explains **why** the system flagged it (which rules triggered and what they mean in plain English)
- Highlights the most concerning factor
- Uses actual numbers from the evaluation (amounts, scores, deviations)
- Avoids technical jargon (no "EWMA", "z-score", "isolation forest")

Explanations are **generated on-demand** (first view) and **cached in Aerospike** — subsequent views are instant. If Ollama is unavailable, the card simply doesn't appear (graceful degradation).

**Feedback-Aware Prompting:** Operators can rate AI explanations as helpful or not helpful (thumbs up/down). Recent "not helpful" explanations are automatically injected as negative examples into the LLM system prompt, so future explanations improve over time based on operator feedback.

### AI Client Risk Narrative

When viewing a client's profile in the Review Queue detail panel, operators can click **"AI Narrative"** to generate a **plain-English behavioral summary** using the LLM. The narrative considers:

- Client transaction patterns (volume, typical amounts, channels used)
- Recent rule trigger history (PASS/ALERT/BLOCK distribution, top triggered rules)
- EWMA behavioral profile stats

The narrative is regenerated on each request (not cached) to reflect the latest data. Available via the dashboard button or directly via `GET /api/v1/analytics/client/{clientId}/narrative`.

### Smart Alert Triage

The **Smart Triage** button in the Review Queue uses the LLM to rank pending alerts by urgency. It analyzes up to 15 pending items — considering composite scores, triggered rules, and evaluation details — then returns a prioritized list with:

- **Rank** and **urgency level** (CRITICAL / HIGH / MEDIUM / LOW)
- **Reasoning** explaining why each alert was prioritized (e.g., "combines high amount deviation with new beneficiary and dormancy reactivation — classic mule pattern")

Results appear as a compact ranked list in the queue panel. Clicking any item opens its detail view. Available via `GET /api/v1/review/queue/triage`.

### Attack Pattern Labeling

Each flagged transaction is automatically classified into an **attack pattern** by the LLM, giving analysts a higher-level understanding beyond individual rule triggers. The pattern label appears as a color-coded badge in the AI Analysis card with a confidence level and brief summary.

Supported patterns:
- **SMURFING** — many small transactions to stay under thresholds
- **MULE_ACCOUNT** — rapid fund dispersal to multiple new beneficiaries
- **ACCOUNT_TAKEOVER** — sudden behavioral change, new device/IP
- **STRUCTURING** — amounts just below reporting thresholds
- **DORMANCY_EXPLOITATION** — reactivated dormant account with suspicious activity
- **VELOCITY_ABUSE** — abnormally high transaction frequency
- **BENEFICIARY_FRAUD** — new or suspicious beneficiary patterns
- **MULTI_VECTOR_ATTACK** — multiple distinct attack patterns combined
- **UNUSUAL_BEHAVIOR** — anomalous but doesn't fit other categories
- **CLEAN** — no significant risk detected

Pattern labels are generated on-demand alongside AI explanations and cached in Aerospike. The `attackPattern` field is included in the `EvaluationResult` response as a JSON string with `pattern`, `confidence`, and `summary`.

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
| `review_feedback_count_total` | Counter | `status` (TRUE_POSITIVE/FALSE_POSITIVE) |
| `review_auto_accepted_count_total` | Counter | — |
| `rule_weight_adjustment_count_total` | Counter | `rule_id` |

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
3. Returns enriched data: EWMA hourly TPS, expected gap (minutes), actual silence duration, last transaction timestamp
4. Sends a WhatsApp/SMS alert for newly-detected silent clients
5. Avoids alert fatigue: no re-alerting until the client resumes transacting

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
├── controller/           # REST controllers (Transactions, Rules, Profiles, Models, Review Queue, Graph)
├── engine/
│   ├── evaluators/       # 16 rule evaluators (15 rule-based + 1 Isolation Forest)
│   └── isolationforest/  # Pure Java IF implementation (tree, node, feature extractor)
├── model/                # Domain models (Transaction, ClientProfile, AnomalyRule, etc.)
├── repository/           # Aerospike data access (8 repositories)
├── seeder/               # Demo data seeder & profile builder
└── service/              # Business logic (evaluation, scoring, profiling, notifications, review queue, auto-tuning)

dashboard_ui/             # Flutter Web dashboard
aerospike/                # Aerospike server configuration
prometheus/               # Prometheus scrape configuration
grafana/provisioning/     # Grafana datasources + pre-built dashboard
```

## Seeded Test Data

Run with `SPRING_PROFILES_ACTIVE=seed` to generate ~100,000+ transactions across 60 days with time-aware patterns:

| Clients | Behavior |
|---------|----------|
| CLIENT-001 to CLIENT-005 | Normal behavioral profiles with business-hour bias (90% during 09–17 UTC, weekends at 30% volume) |
| CLIENT-006 to CLIENT-010 | Normal base + injected anomalies in last 2 days |
| CLIENT-007, CLIENT-008, CLIENT-009 | Mule network patterns — 7 shared beneficiaries with fan-in=3, ~288 cross-client transactions |
| CLIENT-011 | Dormant account — 56 days active, then 2+ day gap with reactivation |

**Seasonal patterns:** Transactions follow realistic time-of-day and day-of-week distributions. Weekdays concentrate 90% of volume during business hours (09–17 UTC) with 10% off-peak. Weekends generate ~30% of weekday volume. This builds rich seasonal profiles with ~8 samples per day-of-week slot and ~2–3 per hour-of-day slot.

**Injected anomalies** (clients 006–010): unusual transaction types, spiked amounts (5–10x normal), TPS bursts (50 txns/hour), structuring patterns (15–25 rapid transfers to same beneficiary), drip structuring (40–60 small txns/day), new-beneficiary fan-out (8–12 new beneficiaries/day), cross-channel splitting (same beneficiary via multiple txn types), 3 AM TPS bursts (off-peak seasonal deviation), and Sunday high-volume surges (weekend seasonal deviation).

Each client has 20–50 unique beneficiaries with power-law distribution.

### On-Demand Demo Data Generation

For quick testing without restarting, use the demo data endpoint:

```bash
curl -s -X POST http://localhost:8080/api/v1/demo/generate \
  -H "Content-Type: application/json" \
  -d '{"clientCount": 5, "txnsPerClient": 200, "anomalyPct": 15}' | python3 -m json.tool
```

This generates clients with EWMA profiles, transactions with realistic patterns, and injects anomalies at the specified percentage. Data is immediately visible in the dashboard.

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
| `risk.rule-defaults.seasonal-deviation-variance-pct` | 80.0 | Seasonal Deviation |
| `risk.rule-defaults.seasonal-min-samples` | 4 | Seasonal Deviation (min slot samples before using seasonal baseline) |
| `risk.rule-defaults.mule-min-fan-in` | 2 | Mule Network (min other senders to flag a beneficiary) |
| `risk.rule-defaults.mule-shared-bene-pct` | 20.0 | Mule Network (% of client's beneficiaries shared with others) |
| `risk.rule-defaults.mule-density-threshold` | 0.3 | Mule Network (network density threshold 0–1) |
| `risk.rule-defaults.mule-composite-threshold` | 25.0 | Mule Network (min composite score to trigger) |
| `risk.rule-defaults.mule-graph-refresh-ms` | 300000 | Mule Network (graph rebuild interval, 5 min) |

## Aerospike Data Model

All data is stored in the `banking` namespace across 12 sets:

| # | Set | Purpose | Key Format |
|---|-----|---------|------------|
| 1 | `transactions` | Raw transaction records | `txnId` |
| 2 | `client_profiles` | EWMA behavioral profiles per client | `clientId` |
| 3 | `anomaly_rules` | Rule definitions (type, weight, params) | `ruleId` |
| 4 | `risk_results` | Evaluation results per transaction | `txnId` |
| 5 | `client_hourly_counters` | Atomic hourly txn count + amount | `clientId:yyyyMMddHH` |
| 6 | `bene_hourly_counters` | Atomic hourly beneficiary count + amount | `clientId:beneKey:yyyyMMddHH` |
| 7 | `client_daily_counters` | Atomic daily txn count + amount | `clientId:yyyyMMdd` |
| 8 | `daily_new_bene_cntrs` | Atomic daily new-beneficiary count | `clientId:newbene:yyyyMMdd` |
| 9 | `if_models` | Serialized Isolation Forest models | `clientId` |
| 10 | `review_queue` | ALERT/BLOCK items for ops review | `txnId` |
| 11 | `rule_weight_history` | Rule weight change audit trail | `ruleId_timestamp` |
| 12 | `ai_feedback` | AI explanation helpful/not-helpful ratings | `txnId` |

Sets 1–4 are core data, 5–8 are atomic counters for real-time aggregation, 9 is ML models, 10–11 support the feedback loop, and 12 stores AI explanation feedback.

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Java 17 |
| Framework | Spring Boot 3.2.5 |
| Build | Gradle 8.7 |
| Database | Aerospike 7.1 (Docker) |
| ML | Isolation Forest (pure Java) |
| LLM | Ollama + llama3.2:1b (native macOS, Metal GPU) |
| Notifications | Twilio SDK 10.1.0 (WhatsApp / SMS) |
| Tracing | Micrometer + OpenTelemetry → Jaeger |
| Metrics | Micrometer + Prometheus + Grafana |
| API Docs | springdoc-openapi (Swagger UI) |
| Dashboard | Flutter Web |
| Containerization | Docker, Docker Compose |
