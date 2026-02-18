package com.bank.anomaly.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Client behavioral profile built from historical transaction data using EWMA statistics")
public class ClientProfile {

    @Schema(description = "Client identifier", example = "CLIENT-001")
    private String clientId;

    @Schema(description = "Transaction count per type", example = "{\"NEFT\": 5400, \"RTGS\": 600}")
    @Builder.Default
    private Map<String, Long> txnTypeCounts = new HashMap<>();

    @Schema(description = "Total number of transactions processed", example = "6000")
    @Builder.Default
    private long totalTxnCount = 0;

    @Schema(description = "EWMA of transaction amounts (rupees)", example = "50234.56")
    @Builder.Default
    private double ewmaAmount = 0.0;

    @Schema(description = "Welford's M2 accumulator for amount variance", example = "1250000000.0")
    @Builder.Default
    private double amountM2 = 0.0;

    @Schema(description = "EWMA of hourly transaction count", example = "8.33")
    @Builder.Default
    private double ewmaHourlyTps = 0.0;

    @Schema(description = "Welford's M2 accumulator for TPS variance", example = "45.6")
    @Builder.Default
    private double tpsM2 = 0.0;

    @Schema(description = "Number of completed hour windows observed", example = "720")
    @Builder.Default
    private long completedHoursCount = 0;

    @Schema(description = "EWMA amount per transaction type", example = "{\"NEFT\": 48500.0, \"RTGS\": 65000.0}")
    @Builder.Default
    private Map<String, Double> avgAmountByType = new HashMap<>();

    @Schema(description = "Welford's M2 per transaction type")
    @Builder.Default
    private Map<String, Double> amountM2ByType = new HashMap<>();

    @Schema(description = "Transaction count per type (for Welford's variance)")
    @Builder.Default
    private Map<String, Long> amountCountByType = new HashMap<>();

    @Schema(description = "EWMA of hourly total transaction amount", example = "416000.0")
    @Builder.Default
    private double ewmaHourlyAmount = 0.0;

    @Schema(description = "Welford's M2 for hourly amount variance")
    @Builder.Default
    private double hourlyAmountM2 = 0.0;

    @Schema(description = "Last profile update timestamp (epoch millis)", example = "1739886764000")
    @Builder.Default
    private long lastUpdated = 0;

    @Schema(description = "Hour bucket key of the last processed hour (for TPS rollover)", example = "CLIENT-001:2025-02-18T14")
    private String lastHourBucket;

    public double getTypeFrequency(String txnType) {
        if (totalTxnCount == 0) return 0.0;
        return txnTypeCounts.getOrDefault(txnType, 0L) / (double) totalTxnCount;
    }

    public double getAmountStdDev() {
        if (totalTxnCount < 2) return 0.0;
        return Math.sqrt(amountM2 / (totalTxnCount - 1));
    }

    public double getTpsStdDev() {
        if (completedHoursCount < 2) return 0.0;
        return Math.sqrt(tpsM2 / (completedHoursCount - 1));
    }

    public double getAmountStdDevForType(String txnType) {
        long count = amountCountByType.getOrDefault(txnType, 0L);
        if (count < 2) return 0.0;
        double m2 = amountM2ByType.getOrDefault(txnType, 0.0);
        return Math.sqrt(m2 / (count - 1));
    }
}
