package com.bank.anomaly.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Parsed intent from a natural language chat query")
public class ChatIntent {
    @Schema(example = "high_risk_transactions", description = "Classified query type")
    private String queryType;

    @Schema(description = "Extracted filter parameters")
    private ChatFilters filters;

    @Schema(example = "clientId", description = "Field to group results by")
    private String groupBy;

    @Schema(example = "10", description = "Maximum number of results to return")
    private Integer limit;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Filter parameters extracted from natural language query")
    public static class ChatFilters {
        @Schema(example = "NEFT", description = "Transaction type filter")
        private String txnType;

        @Schema(example = "60", description = "Time range in minutes from now")
        private Integer timeRangeMinutes;

        @Schema(example = "CLIENT-001", description = "Client ID filter")
        private String clientId;

        @Schema(example = "HIGH", description = "Risk level filter")
        private String riskLevel;

        @Schema(example = "ALERT", description = "Action filter: ALERT or BLOCK")
        private String action;

        @Schema(example = "PENDING", description = "Feedback status filter")
        private String feedbackStatus;
    }
}
