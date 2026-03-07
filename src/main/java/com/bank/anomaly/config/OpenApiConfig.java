package com.bank.anomaly.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI anomalyDetectionOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Anomaly Detection API")
                        .version("1.0.0")
                        .description(
                                "Real-time behavioral anomaly detection system for banking transactions.\n\n" +
                                "**Evaluation Pipeline:**\n" +
                                "1. Receive transaction via `POST /transactions/evaluate`\n" +
                                "2. Load/create client behavioral profile (EWMA stats)\n" +
                                "3. Evaluate against all active anomaly rules (15 rule-based + 1 Isolation Forest)\n" +
                                "4. Compute weighted composite risk score (0–100) with breadth multiplier\n" +
                                "5. Determine action: **PASS** (<30), **ALERT** (30–70), **BLOCK** (>=70)\n\n" +
                                "**Rule Types (16):**\n" +
                                "- `TRANSACTION_TYPE_ANOMALY` — flags rarely/never used transaction types\n" +
                                "- `TPS_SPIKE` — flags hourly transaction count spikes\n" +
                                "- `AMOUNT_ANOMALY` — flags unusually large single transactions\n" +
                                "- `HOURLY_AMOUNT_ANOMALY` — flags hourly total amount spikes\n" +
                                "- `AMOUNT_PER_TYPE_ANOMALY` — flags unusual amounts for specific transaction types\n" +
                                "- `BENEFICIARY_RAPID_REPEAT` — repeated transactions to same beneficiary in 1 hour\n" +
                                "- `BENEFICIARY_CONCENTRATION` — disproportionate volume to a single beneficiary\n" +
                                "- `BENEFICIARY_AMOUNT_REPETITION` — repeated identical amounts (threshold evasion)\n" +
                                "- `DAILY_CUMULATIVE_AMOUNT` — low-and-slow drip structuring\n" +
                                "- `NEW_BENEFICIARY_VELOCITY` — too many new beneficiaries per day\n" +
                                "- `DORMANCY_REACTIVATION` — sudden activity after extended inactivity\n" +
                                "- `CROSS_CHANNEL_BENEFICIARY_AMOUNT` — same beneficiary across multiple txn types\n" +
                                "- `SEASONAL_DEVIATION` — hour-of-day/day-of-week baseline deviation\n" +
                                "- `MULE_NETWORK` — graph-based mule network detection via shared beneficiaries\n" +
                                "- `TEMPORAL_RULE_CORRELATION` — repeated rule triggers for same client in time window\n" +
                                "- `ISOLATION_FOREST` — ML-based multi-dimensional anomaly detection\n\n" +
                                "**Seeded Clients:** CLIENT-001 to CLIENT-011 (CLIENT-006 to CLIENT-010 have injected anomalies, CLIENT-011 is dormant)")
                        .contact(new Contact().name("Anomaly Detection Team")));
    }
}
