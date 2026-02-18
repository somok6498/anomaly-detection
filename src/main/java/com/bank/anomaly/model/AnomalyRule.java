package com.bank.anomaly.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Configuration for an anomaly detection rule")
public class AnomalyRule {

    @Schema(description = "Unique rule identifier", example = "RULE-IF")
    private String ruleId;

    @Schema(description = "Rule display name", example = "Isolation Forest")
    private String name;

    @Schema(description = "Detailed description of what the rule detects",
            example = "ML-based detection of multi-dimensional anomalies that individual rules miss")
    private String description;

    @Schema(description = "The type of anomaly this rule detects", example = "ISOLATION_FOREST")
    private RuleType ruleType;

    @Schema(description = "Threshold percentage. For rule-based: flag if value exceeds baseline by this %. For IF: anomaly score threshold (60 = 0.60)",
            example = "60.0")
    private double variancePct;

    @Schema(description = "Weight of this rule in composite score (higher = more influence)", example = "2.0")
    @Builder.Default
    private double riskWeight = 1.0;

    @Schema(description = "Whether this rule is currently active", example = "true")
    @Builder.Default
    private boolean enabled = true;

    @Schema(description = "Rule-specific parameters", example = "{\"numTrees\": \"100\", \"sampleSize\": \"256\"}")
    @Builder.Default
    private Map<String, String> params = new HashMap<>();

    public double getParamAsDouble(String key, double defaultValue) {
        String val = params.get(key);
        if (val == null) return defaultValue;
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public long getParamAsLong(String key, long defaultValue) {
        String val = params.get(key);
        if (val == null) return defaultValue;
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
