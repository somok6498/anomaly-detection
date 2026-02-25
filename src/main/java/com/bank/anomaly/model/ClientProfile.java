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

    // --- Beneficiary tracking ---

    @Schema(description = "Transaction count per beneficiary (key = IFSC:Account)")
    @Builder.Default
    private Map<String, Long> beneficiaryTxnCounts = new HashMap<>();

    @Schema(description = "Total distinct beneficiaries this client has transacted with", example = "35")
    @Builder.Default
    private long distinctBeneficiaryCount = 0;

    @Schema(description = "EWMA of amount per beneficiary")
    @Builder.Default
    private Map<String, Double> ewmaAmountByBeneficiary = new HashMap<>();

    @Schema(description = "Welford's M2 per beneficiary for amount variance")
    @Builder.Default
    private Map<String, Double> amountM2ByBeneficiary = new HashMap<>();

    // --- Daily cumulative tracking (Rule 10) ---

    @Schema(description = "EWMA of daily cumulative transaction amount (rupees)", example = "1200000.0")
    @Builder.Default
    private double ewmaDailyAmount = 0.0;

    @Schema(description = "Welford's M2 accumulator for daily amount variance")
    @Builder.Default
    private double dailyAmountM2 = 0.0;

    @Schema(description = "Number of completed calendar days observed", example = "28")
    @Builder.Default
    private long completedDaysCount = 0;

    // --- Daily new beneficiary velocity tracking (Rule 11) ---

    @Schema(description = "EWMA of daily new-beneficiary count", example = "1.2")
    @Builder.Default
    private double ewmaDailyNewBeneficiaries = 0.0;

    @Schema(description = "Welford's M2 accumulator for daily new-beneficiary count variance")
    @Builder.Default
    private double dailyNewBeneM2 = 0.0;

    @Schema(description = "Number of completed calendar days used in new-bene EWMA", example = "28")
    @Builder.Default
    private long completedDaysForBeneCount = 0;

    // --- Day bucket tracking ---

    @Schema(description = "Day bucket key of the last processed day (for daily rollover)", example = "20260223")
    private String lastDayBucket;

    // --- Seasonal profile maps (EWMA + Welford M2 + count per time slot) ---

    @Schema(description = "Seasonal EWMA of hourly TPS by hour-of-day (keys: 00-23)")
    @Builder.Default
    private Map<String, Double> seasonalHourlyTps = new HashMap<>();

    @Schema(description = "Welford M2 for hourly TPS by hour-of-day")
    @Builder.Default
    private Map<String, Double> seasonalHourlyTpsM2 = new HashMap<>();

    @Schema(description = "Sample count for hourly TPS by hour-of-day")
    @Builder.Default
    private Map<String, Long> seasonalHourlyTpsCnt = new HashMap<>();

    @Schema(description = "Seasonal EWMA of hourly amount by hour-of-day (keys: 00-23)")
    @Builder.Default
    private Map<String, Double> seasonalHourlyAmt = new HashMap<>();

    @Schema(description = "Welford M2 for hourly amount by hour-of-day")
    @Builder.Default
    private Map<String, Double> seasonalHourlyAmtM2 = new HashMap<>();

    @Schema(description = "Sample count for hourly amount by hour-of-day")
    @Builder.Default
    private Map<String, Long> seasonalHourlyAmtCnt = new HashMap<>();

    @Schema(description = "Seasonal EWMA of daily amount by day-of-week (keys: 1-7, Mon=1)")
    @Builder.Default
    private Map<String, Double> seasonalDailyAmt = new HashMap<>();

    @Schema(description = "Welford M2 for daily amount by day-of-week")
    @Builder.Default
    private Map<String, Double> seasonalDailyAmtM2 = new HashMap<>();

    @Schema(description = "Sample count for daily amount by day-of-week")
    @Builder.Default
    private Map<String, Long> seasonalDailyAmtCnt = new HashMap<>();

    @Schema(description = "Seasonal EWMA of daily TPS by day-of-week (keys: 1-7, Mon=1)")
    @Builder.Default
    private Map<String, Double> seasonalDailyTps = new HashMap<>();

    @Schema(description = "Welford M2 for daily TPS by day-of-week")
    @Builder.Default
    private Map<String, Double> seasonalDailyTpsM2 = new HashMap<>();

    @Schema(description = "Sample count for daily TPS by day-of-week")
    @Builder.Default
    private Map<String, Long> seasonalDailyTpsCnt = new HashMap<>();

    public double getBeneficiaryConcentration(String beneficiaryKey) {
        if (totalTxnCount == 0 || beneficiaryKey == null) return 0.0;
        return beneficiaryTxnCounts.getOrDefault(beneficiaryKey, 0L) / (double) totalTxnCount;
    }

    public double getAmountStdDevForBeneficiary(String beneficiaryKey) {
        long count = beneficiaryTxnCounts.getOrDefault(beneficiaryKey, 0L);
        if (count < 2) return 0.0;
        double m2 = amountM2ByBeneficiary.getOrDefault(beneficiaryKey, 0.0);
        return Math.sqrt(m2 / (count - 1));
    }

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

    public double getDailyAmountStdDev() {
        if (completedDaysCount < 2) return 0.0;
        return Math.sqrt(dailyAmountM2 / (completedDaysCount - 1));
    }

    public double getDailyNewBeneStdDev() {
        if (completedDaysForBeneCount < 2) return 0.0;
        return Math.sqrt(dailyNewBeneM2 / (completedDaysForBeneCount - 1));
    }

    public double getSeasonalHourlyTpsStdDev(String slot) {
        long cnt = seasonalHourlyTpsCnt.getOrDefault(slot, 0L);
        if (cnt < 2) return 0.0;
        return Math.sqrt(seasonalHourlyTpsM2.getOrDefault(slot, 0.0) / (cnt - 1));
    }

    public double getSeasonalHourlyAmtStdDev(String slot) {
        long cnt = seasonalHourlyAmtCnt.getOrDefault(slot, 0L);
        if (cnt < 2) return 0.0;
        return Math.sqrt(seasonalHourlyAmtM2.getOrDefault(slot, 0.0) / (cnt - 1));
    }

    public double getSeasonalDailyAmtStdDev(String slot) {
        long cnt = seasonalDailyAmtCnt.getOrDefault(slot, 0L);
        if (cnt < 2) return 0.0;
        return Math.sqrt(seasonalDailyAmtM2.getOrDefault(slot, 0.0) / (cnt - 1));
    }

    public double getSeasonalDailyTpsStdDev(String slot) {
        long cnt = seasonalDailyTpsCnt.getOrDefault(slot, 0L);
        if (cnt < 2) return 0.0;
        return Math.sqrt(seasonalDailyTpsM2.getOrDefault(slot, 0.0) / (cnt - 1));
    }
}
