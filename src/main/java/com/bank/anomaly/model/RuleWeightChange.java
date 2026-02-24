package com.bank.anomaly.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleWeightChange {
    private String ruleId;
    private double oldWeight;
    private double newWeight;
    private int tpCount;
    private int fpCount;
    private double tpFpRatio;
    private long adjustedAt;
}
