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
@Schema(description = "Operator feedback on AI-generated explanation quality")
public class AiFeedback {
    @Schema(example = "CLIENT-001-TXN-000042", description = "Transaction ID")
    private String txnId;

    @Schema(example = "true", description = "Whether the AI explanation was helpful")
    private boolean helpful;

    @Schema(example = "ops-analyst-1", description = "Operator who submitted feedback")
    private String operatorId;

    @Schema(example = "1709856000000", description = "Epoch millis when feedback was submitted")
    private long timestamp;
}
