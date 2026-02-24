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

    @Schema(description = "Transaction type (configurable via risk.transaction-types in application.yml)", example = "NEFT")
    private String txnType;

    @Schema(description = "Transaction amount in rupees", example = "50000.00")
    private double amount;

    @Schema(description = "Transaction timestamp in epoch milliseconds. Defaults to current time if not provided.", example = "1739886764000")
    private long timestamp;

    @Schema(description = "Beneficiary bank account number", example = "1234567890")
    private String beneficiaryAccount;

    @Schema(description = "Beneficiary bank IFSC code", example = "HDFC0001234")
    private String beneficiaryIfsc;

    /**
     * Returns a canonical beneficiary identifier: IFSC:Account.
     * Returns null if beneficiary data is absent.
     */
    public String getBeneficiaryKey() {
        if (beneficiaryAccount == null || beneficiaryAccount.isBlank()) return null;
        String ifsc = (beneficiaryIfsc != null && !beneficiaryIfsc.isBlank()) ? beneficiaryIfsc : "UNKNOWN";
        return ifsc + ":" + beneficiaryAccount;
    }
}
