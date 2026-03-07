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
@Schema(description = "An item in the analyst review queue")
public class ReviewQueueItem {
    @Schema(example = "CLIENT-001-TXN-000042", description = "Transaction ID")
    private String txnId;

    @Schema(example = "CLIENT-001", description = "Client identifier")
    private String clientId;

    @Schema(example = "ALERT", description = "Recommended action: ALERT or BLOCK")
    private String action;

    @Schema(example = "67.5", description = "Composite risk score (0-100)")
    private double compositeScore;

    @Schema(example = "HIGH", description = "Risk level: LOW, MEDIUM, HIGH, or CRITICAL")
    private String riskLevel;

    @Schema(example = "[\"RULE-AMT\", \"RULE-TPS\"]", description = "IDs of rules that triggered")
    private List<String> triggeredRuleIds;

    @Schema(example = "1709856000000", description = "Epoch millis when item was enqueued")
    private long enqueuedAt;

    @Schema(example = "PENDING", description = "Feedback status: PENDING, TRUE_POSITIVE, FALSE_POSITIVE, AUTO_ACCEPTED")
    private ReviewStatus feedbackStatus;

    @Schema(example = "0", description = "Epoch millis when feedback was given (0 until acted upon)")
    private long feedbackAt;

    @Schema(example = "ops-analyst-1", description = "Operator ID or SYSTEM for auto-accept")
    private String feedbackBy;

    @Schema(example = "1709942400000", description = "Epoch millis deadline for auto-accept")
    private long autoAcceptDeadline;
}
