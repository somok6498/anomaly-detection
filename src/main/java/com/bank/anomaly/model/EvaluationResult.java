package com.bank.anomaly.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Result of evaluating a transaction against all active anomaly rules")
public class EvaluationResult {

    @Schema(description = "Transaction ID that was evaluated", example = "CLIENT-001-TXN-000001")
    private String txnId;

    @Schema(description = "Client ID", example = "CLIENT-001")
    private String clientId;

    @Schema(description = "Weighted composite risk score (0-100). PASS < 30, ALERT 30-70, BLOCK >= 70", example = "42.5")
    private double compositeScore;

    @Schema(description = "Risk level: LOW (<30), MEDIUM (30-60), HIGH (60-80), CRITICAL (>=80)", example = "MEDIUM")
    private RiskLevel riskLevel;

    @Schema(description = "Recommended action based on composite score", example = "ALERT", allowableValues = {"PASS", "ALERT", "BLOCK"})
    private String action;

    @Schema(description = "Individual rule evaluation results")
    private List<RuleResult> ruleResults;

    @Schema(description = "Evaluation timestamp in epoch milliseconds", example = "1739886764000")
    private long evaluatedAt;

    @Schema(description = "Number of rules that triggered for this transaction", example = "3")
    private int triggeredRuleCount;

    @Schema(description = "Breadth bonus applied to the base score (e.g., 0.06 means 6% boost from multiple triggered rules)", example = "0.06")
    private double breadthBonus;

    @Schema(description = "LLM-generated human-readable explanation of why this transaction was flagged")
    private String aiExplanation;
}
