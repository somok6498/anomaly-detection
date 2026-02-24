package com.bank.anomaly.engine;

import lombok.Builder;
import lombok.Data;

/**
 * Additional runtime context passed to rule evaluators that can't be derived
 * from the static client profile alone (e.g., current hour's live counters).
 */
@Data
@Builder
public class EvaluationContext {
    // Current hour's transaction count for this client (from atomic counter)
    private long currentHourlyTxnCount;

    // Current hour's total amount for this client (in paise, from atomic counter)
    private long currentHourlyAmountPaise;

    // Beneficiary window data — same beneficiary txns/amount in the current hour
    private long currentWindowBeneficiaryTxnCount;
    private long currentWindowBeneficiaryAmountPaise;
    private String currentBeneficiaryKey;

    // Current day's transaction count and amount for this client (from daily atomic counter)
    private long currentDailyTxnCount;
    private long currentDailyAmountPaise;

    // Count of brand-new (first-time) beneficiaries transacted with today
    private long currentDailyNewBeneficiaryCount;

    // Daily total amount to the current beneficiary across all txn types (for cross-channel detection)
    private long currentDailyBeneficiaryAmountPaise;
}
