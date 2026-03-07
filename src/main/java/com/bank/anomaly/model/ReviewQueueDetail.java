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
@Schema(description = "Full detail view of a review queue item including evaluation, transaction, and client profile")
public class ReviewQueueDetail {
    @Schema(description = "The review queue item")
    private ReviewQueueItem queueItem;

    @Schema(description = "Risk evaluation result with rule scores and AI explanation")
    private EvaluationResult evaluation;

    @Schema(description = "The original transaction")
    private Transaction transaction;

    @Schema(description = "Client profile with historical statistics")
    private ClientProfile clientProfile;
}
