package com.bank.anomaly.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

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
}
