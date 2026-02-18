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
                                "3. Evaluate against all active anomaly rules (5 rule-based + 1 Isolation Forest)\n" +
                                "4. Compute weighted composite risk score (0-100)\n" +
                                "5. Determine action: **PASS** (<30), **ALERT** (30-70), **BLOCK** (>=70)\n\n" +
                                "**Rule Types:**\n" +
                                "- `TRANSACTION_TYPE_ANOMALY` — flags rarely/never used transaction types\n" +
                                "- `TPS_SPIKE` — flags hourly transaction count spikes\n" +
                                "- `AMOUNT_ANOMALY` — flags unusually large single transactions\n" +
                                "- `HOURLY_AMOUNT_ANOMALY` — flags hourly total amount spikes\n" +
                                "- `AMOUNT_PER_TYPE_ANOMALY` — flags unusual amounts for specific transaction types\n" +
                                "- `ISOLATION_FOREST` — ML-based multi-dimensional anomaly detection\n\n" +
                                "**Seeded Clients:** CLIENT-001 to CLIENT-010 (CLIENT-006 to CLIENT-010 have injected anomalies)")
                        .contact(new Contact().name("Anomaly Detection Team")));
    }
}
