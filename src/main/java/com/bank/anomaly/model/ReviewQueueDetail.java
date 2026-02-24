package com.bank.anomaly.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewQueueDetail {
    private ReviewQueueItem queueItem;
    private EvaluationResult evaluation;
    private Transaction transaction;
    private ClientProfile clientProfile;
}
