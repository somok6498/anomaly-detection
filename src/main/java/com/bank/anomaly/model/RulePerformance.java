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
@Schema(description = "Performance statistics for an anomaly detection rule")
public class RulePerformance {
    @Schema(example = "RULE-AMT", description = "Rule identifier")
    private String ruleId;

    @Schema(example = "Transaction Amount Anomaly", description = "Human-readable rule name")
    private String ruleName;

    @Schema(example = "AMOUNT_ANOMALY", description = "Rule type enum value")
    private String ruleType;

    @Schema(example = "2.5", description = "Current risk weight")
    private double currentWeight;

    @Schema(example = "150", description = "Total times this rule triggered")
    private int triggerCount;

    @Schema(example = "10", description = "True positive feedback count")
    private int tpCount;

    @Schema(example = "3", description = "False positive feedback count")
    private int fpCount;

    @Schema(example = "76.9", description = "Precision percentage: TP / (TP + FP) * 100")
    private double precision;
}
