package com.bank.anomaly.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RulePerformance {
    private String ruleId;
    private String ruleName;
    private String ruleType;
    private double currentWeight;
    private int triggerCount;
    private int tpCount;
    private int fpCount;
    private double precision;
}
