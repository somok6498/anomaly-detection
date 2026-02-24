package com.bank.anomaly.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "risk.feedback")
public class FeedbackConfig {
    private long autoAcceptTimeoutMs = 3600000;          // 1 hour
    private int autoAcceptCheckIntervalSeconds = 60;
    private int tuningIntervalHours = 6;
    private int minSamplesForTuning = 50;
    private double weightFloor = 0.5;
    private double weightCeiling = 5.0;
    private double maxAdjustmentPct = 0.10;              // 10%
}
