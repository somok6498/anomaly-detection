package com.bank.anomaly.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
@Schema(description = "Structured chat response with optional tabular data")
public class ChatResponse {
    @Schema(example = "Found 5 high-risk NEFT transactions in the last hour", description = "Natural language summary of results")
    private String summary;

    @Schema(example = "[\"txnId\", \"clientId\", \"amount\", \"riskLevel\"]", description = "Column headers for tabular results")
    private List<String> columns;

    @Schema(description = "Row data matching the columns")
    private List<List<String>> rows;

    @Schema(example = "high_risk_transactions", description = "Classified query type")
    private String queryType;

    @Schema(example = "true", description = "Whether the response contains tabular data")
    private boolean isTabular;

    @Schema(example = "null", description = "Error message if query could not be processed")
    private String errorMessage;
}
