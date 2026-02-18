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
}
