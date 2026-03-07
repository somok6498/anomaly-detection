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
@Schema(description = "Record of a rule weight adjustment based on feedback")
public class RuleWeightChange {
    @Schema(example = "RULE-AMT", description = "Rule identifier")
    private String ruleId;

    @Schema(example = "2.0", description = "Weight before adjustment")
    private double oldWeight;

    @Schema(example = "2.4", description = "Weight after adjustment")
    private double newWeight;

    @Schema(example = "8", description = "True positive count at time of adjustment")
    private int tpCount;

    @Schema(example = "2", description = "False positive count at time of adjustment")
    private int fpCount;

    @Schema(example = "4.0", description = "TP/FP ratio used to compute new weight")
    private double tpFpRatio;

    @Schema(example = "1709856000000", description = "Epoch millis when weight was adjusted")
    private long adjustedAt;
}
