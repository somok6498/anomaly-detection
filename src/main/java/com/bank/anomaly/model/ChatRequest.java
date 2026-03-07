package com.bank.anomaly.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Natural language chat query request")
public class ChatRequest {
    @Schema(example = "Show me the top 5 high-risk NEFT transactions", description = "Natural language question about transactions or risk data")
    private String message;
}
