package com.bank.anomaly.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "risk")
public class RiskThresholdConfig {

    // Composite score threshold above which transactions are alerted
    private double alertThreshold = 30.0;

    // Composite score threshold above which transactions are blocked
    private double blockThreshold = 70.0;

    // EWMA smoothing factor (0 < alpha <= 1). Lower = slower adaptation.
    // 0.01 is appropriate for ~30-day windows with high transaction volume.
    private double ewmaAlpha = 0.01;

    // Minimum number of transactions in a client profile before rules apply.
    // Below this threshold, all transactions pass (new client grace period).
    private long minProfileTxns = 20;

    // How often (in seconds) to refresh the in-memory rule cache from Aerospike.
    private int ruleCacheRefreshSeconds = 60;

    // Configurable transaction types — extensible without code changes (e.g., add CBDC).
    private List<String> transactionTypes = List.of("NEFT", "RTGS", "IMPS", "UPI", "IFT");

    // Rule default parameters — fallback when not specified in rule.params or rule.variancePct.
    private RuleDefaults ruleDefaults = new RuleDefaults();

    // Silence detection — background scheduler config
    private SilenceDetection silenceDetection = new SilenceDetection();

    @Data
    public static class RuleDefaults {
        // Existing rules
        private double amountAnomalyVariancePct = 100.0;
        private double amountPerTypeVariancePct = 150.0;
        private long minTypeSamples = 10;
        private double hourlyAmountVariancePct = 80.0;
        private double tpsSpikeVariancePct = 50.0;
        private double minTypeFrequencyPct = 5.0;
        private int minRepeatCount = 5;
        private double beneficiaryConcentrationVariancePct = 200.0;
        private int minDistinctBeneficiaries = 5;
        private double absMinConcentrationPct = 5.0;
        private int minBeneficiaryTxns = 3;
        private double maxCvPct = 10.0;
        private double isolationForestThreshold = 60.0;
        // New rules
        private double dailyCumulativeVariancePct = 150.0;
        private int dailyCumulativeMinDays = 3;
        private int newBeneMaxPerDay = 5;
        private double newBeneVariancePct = 200.0;
        private int newBeneMinProfileDays = 3;
        private int dormancyDays = 30;
        private double crossChannelBeneVariancePct = 150.0;
        private int crossChannelBeneMinDays = 3;
        private double seasonalDeviationVariancePct = 80.0;
        private int seasonalMinSamples = 4;
    }

    @Data
    public static class SilenceDetection {
        private boolean enabled = true;
        private int checkIntervalMinutes = 5;
        private double silenceMultiplier = 3.0;
        private double minExpectedTps = 1.0;
        private long minCompletedHours = 48;
    }
}
