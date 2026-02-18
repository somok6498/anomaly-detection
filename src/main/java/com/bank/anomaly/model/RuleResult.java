package com.bank.anomaly.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Evaluation result from a single anomaly rule")
public class RuleResult {

    @Schema(description = "Rule identifier", example = "RULE-IF")
    private String ruleId;

    @Schema(description = "Rule display name", example = "Isolation Forest")
    private String ruleName;

    @Schema(description = "Type of anomaly rule", example = "ISOLATION_FOREST")
    private RuleType ruleType;

    @Schema(description = "Whether this rule flagged the transaction as anomalous", example = "true")
    private boolean triggered;

    @Schema(description = "How far the value deviated from baseline (as %)", example = "15.0")
    private double deviationPct;

    @Schema(description = "This rule's risk contribution score (0-100) before weighting", example = "42.5")
    private double partialScore;

    @Schema(description = "Rule's weight in composite score calculation", example = "2.0")
    private double riskWeight;

    @Schema(description = "Human-readable explanation of the evaluation",
            example = "Isolation Forest: score=0.690 (threshold=0.60). Multi-dimensional anomaly detected. Top factors: Amount Z-score=2.67 (contribution=0.045)")
    private String reason;
}
