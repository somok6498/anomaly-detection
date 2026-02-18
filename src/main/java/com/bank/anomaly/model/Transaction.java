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
@Schema(description = "A banking transaction submitted for anomaly evaluation")
public class Transaction {

    @Schema(description = "Unique transaction identifier", example = "CLIENT-001-TXN-000001")
    private String txnId;

    @Schema(description = "Client identifier", example = "CLIENT-001")
    private String clientId;

    @Schema(description = "Transaction type", example = "NEFT", allowableValues = {"NEFT", "RTGS", "IMPS", "UPI", "IFT"})
    private String txnType;

    @Schema(description = "Transaction amount in rupees", example = "50000.00")
    private double amount;

    @Schema(description = "Transaction timestamp in epoch milliseconds. Defaults to current time if not provided.", example = "1739886764000")
    private long timestamp;
}
