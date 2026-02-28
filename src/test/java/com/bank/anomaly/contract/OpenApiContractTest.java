package com.bank.anomaly.contract;

import com.bank.anomaly.config.TestAerospikeConfig;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test that validates the OpenAPI spec structure.
 * Ensures all endpoints and critical schemas are present,
 * protecting consumers from accidental schema drift.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestAerospikeConfig.class)
@ActiveProfiles("test")
class OpenApiContractTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void openApiSpec_isAccessible() {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty();
    }

    @Test
    void openApiSpec_containsAllEndpointPaths() {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);
        DocumentContext json = JsonPath.parse(response.getBody());
        Map<String, Object> paths = json.read("$.paths");

        // Transaction endpoints
        assertThat(paths).containsKey("/api/v1/transactions/evaluate");
        assertThat(paths).containsKey("/api/v1/transactions/{txnId}");
        assertThat(paths).containsKey("/api/v1/transactions/client/{clientId}");
        assertThat(paths).containsKey("/api/v1/transactions/results/{txnId}");
        assertThat(paths).containsKey("/api/v1/transactions/results/client/{clientId}");

        // Rule endpoints
        assertThat(paths).containsKey("/api/v1/rules");
        assertThat(paths).containsKey("/api/v1/rules/{ruleId}");

        // Review queue endpoints
        assertThat(paths).containsKey("/api/v1/review/queue");
        assertThat(paths).containsKey("/api/v1/review/queue/{txnId}");
        assertThat(paths).containsKey("/api/v1/review/queue/{txnId}/feedback");
        assertThat(paths).containsKey("/api/v1/review/queue/bulk-feedback");
        assertThat(paths).containsKey("/api/v1/review/stats");
        assertThat(paths).containsKey("/api/v1/review/weight-history");

        // Profile endpoint
        assertThat(paths).containsKey("/api/v1/profiles/{clientId}");

        // Model endpoints
        assertThat(paths).containsKey("/api/v1/models/train/{clientId}");
        assertThat(paths).containsKey("/api/v1/models/train");
        assertThat(paths).containsKey("/api/v1/models/{clientId}");

        // Analytics endpoints
        assertThat(paths).containsKey("/api/v1/analytics/rules/performance");
        assertThat(paths).containsKey("/api/v1/analytics/graph/client/{clientId}/network");

        // Graph endpoints
        assertThat(paths).containsKey("/api/v1/graph/status");
        assertThat(paths).containsKey("/api/v1/graph/beneficiary/{ifsc}/{account}");
        assertThat(paths).containsKey("/api/v1/graph/client/{clientId}");

        // Silence detection endpoints
        assertThat(paths).containsKey("/api/v1/silence");
        assertThat(paths).containsKey("/api/v1/silence/check");
    }

    @Test
    void openApiSpec_containsCriticalSchemas() {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);
        DocumentContext json = JsonPath.parse(response.getBody());
        Map<String, Object> schemas = json.read("$.components.schemas");

        assertThat(schemas).containsKey("Transaction");
        assertThat(schemas).containsKey("EvaluationResult");
        assertThat(schemas).containsKey("AnomalyRule");
        assertThat(schemas).containsKey("ClientProfile");
        assertThat(schemas).containsKey("RuleResult");
    }

    @Test
    void openApiSpec_transactionAndEvalSchemas_haveRequiredFields() {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);
        DocumentContext json = JsonPath.parse(response.getBody());

        // Transaction schema fields
        Map<String, Object> txnProps = json.read("$.components.schemas.Transaction.properties");
        assertThat(txnProps).containsKey("txnId");
        assertThat(txnProps).containsKey("clientId");
        assertThat(txnProps).containsKey("txnType");
        assertThat(txnProps).containsKey("amount");
        assertThat(txnProps).containsKey("timestamp");

        // EvaluationResult schema fields
        Map<String, Object> evalProps = json.read("$.components.schemas.EvaluationResult.properties");
        assertThat(evalProps).containsKey("compositeScore");
        assertThat(evalProps).containsKey("riskLevel");
        assertThat(evalProps).containsKey("action");
        assertThat(evalProps).containsKey("ruleResults");
    }
}
