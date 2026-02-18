# Anomaly Detection System

Real-time behavioral anomaly detection for banking transactions using rule-based evaluation + Isolation Forest ML model.

## Architecture

- **Spring Boot 3.2.5** (Java 17) REST API
- **Aerospike** in-memory database (via Docker)
- **5 rule-based evaluators** + **1 ML evaluator** (Isolation Forest)
- **EWMA** (Exponential Weighted Moving Average) client behavioral profiling
- **Swagger UI** for interactive API exploration
- **Flutter Web** dashboard (pre-built, served as static assets)

### Evaluation Pipeline

1. Receive transaction via `POST /api/v1/transactions/evaluate`
2. Load/create client behavioral profile (EWMA stats)
3. Evaluate against all active anomaly rules
4. Compute weighted composite risk score (0-100)
5. Determine action: **PASS** (<30), **ALERT** (30-70), **BLOCK** (>=70)

### Rule Types

| Rule | What it detects |
|------|-----------------|
| `TRANSACTION_TYPE_ANOMALY` | Rarely/never used transaction types |
| `TPS_SPIKE` | Hourly transaction count spikes |
| `AMOUNT_ANOMALY` | Unusually large single transactions |
| `HOURLY_AMOUNT_ANOMALY` | Hourly total amount spikes |
| `AMOUNT_PER_TYPE_ANOMALY` | Unusual amounts for specific types |
| `ISOLATION_FOREST` | ML-based multi-dimensional anomaly detection |

## Prerequisites

- **Java 17+**
- **Docker** and **Docker Compose**

## Quick Start

### 1. Start Aerospike

```bash
docker-compose up -d
```

Verify it's running:
```bash
docker ps | grep aerospike
```

### 2. Seed data and start the app

First run — seeds 10 clients with historical transactions, builds behavioral profiles, and trains Isolation Forest models:

```bash
./gradlew bootRun --args='--spring.profiles.active=seed'
```

Wait for logs to show seeding complete, then stop (`Ctrl+C`) and start normally:

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

### 4. Explore

- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **Dashboard**: http://localhost:8080/index.html
- **OpenAPI spec**: http://localhost:8080/v3/api-docs

## Example: Evaluate a Transaction

```bash
curl -s -X POST http://localhost:8080/api/v1/transactions/evaluate \
  -H "Content-Type: application/json" \
  -d '{
    "txnId": "TEST-001",
    "clientId": "CLIENT-001",
    "txnType": "NEFT",
    "amount": 90000,
    "timestamp": '$(date +%s000)'
  }' | python3 -m json.tool
```

## Project Structure

```
src/main/java/com/bank/anomaly/
├── config/          # Aerospike & OpenAPI configuration
├── controller/      # REST controllers (Transactions, Rules, Profiles, Models)
├── engine/
│   ├── evaluators/  # Rule evaluators (5 rule-based + 1 Isolation Forest)
│   └── isolationforest/  # Pure Java IF implementation
├── model/           # Domain models (Transaction, ClientProfile, AnomalyRule, etc.)
├── repository/      # Aerospike data access
├── seeder/          # Data seeder & profile builder
└── service/         # Business logic services
```

## Seeded Test Data

- **CLIENT-001 to CLIENT-005**: Normal behavioral profiles
- **CLIENT-006 to CLIENT-010**: Profiles with injected anomalies

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/transactions/evaluate` | Evaluate a transaction |
| GET | `/api/v1/transactions/{txnId}` | Get transaction by ID |
| GET | `/api/v1/transactions/client/{clientId}` | List client transactions |
| GET | `/api/v1/transactions/results/{txnId}` | Get evaluation result |
| GET | `/api/v1/transactions/results/client/{clientId}` | List client results |
| GET | `/api/v1/profiles/{clientId}` | Get client profile |
| GET | `/api/v1/rules` | List all rules |
| POST | `/api/v1/rules` | Create a rule |
| PUT | `/api/v1/rules/{ruleId}` | Update a rule |
| DELETE | `/api/v1/rules/{ruleId}` | Delete a rule |
| POST | `/api/v1/models/train/{clientId}` | Train IF model for client |
| POST | `/api/v1/models/train` | Train IF models for all clients |
| GET | `/api/v1/models/{clientId}` | Get model metadata |

## Tech Stack

- Java 17, Spring Boot 3.2.5
- Aerospike 7.1 (Docker)
- Lombok, Jackson
- springdoc-openapi (Swagger UI)
- Flutter Web (dashboard)
- Gradle 8.7
