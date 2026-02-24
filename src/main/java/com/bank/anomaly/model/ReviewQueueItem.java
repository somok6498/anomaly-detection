package com.bank.anomaly.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewQueueItem {
    private String txnId;
    private String clientId;
    private String action;              // "ALERT" or "BLOCK"
    private double compositeScore;
    private String riskLevel;
    private List<String> triggeredRuleIds;
    private long enqueuedAt;
    private ReviewStatus feedbackStatus;
    private long feedbackAt;            // 0 until acted upon
    private String feedbackBy;          // operator ID or "SYSTEM" for auto-accept
    private long autoAcceptDeadline;    // enqueuedAt + timeout
}
