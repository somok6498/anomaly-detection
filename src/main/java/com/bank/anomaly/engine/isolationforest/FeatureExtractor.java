package com.bank.anomaly.engine.isolationforest;

import com.bank.anomaly.engine.EvaluationContext;
import com.bank.anomaly.model.ClientProfile;
import com.bank.anomaly.model.Transaction;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Extracts a 6-dimensional feature vector from a transaction relative to the client's profile.
 *
 * Features:
 *   [0] Amount Z-score: (amount - ewmaAmount) / amountStdDev
 *   [1] Type frequency: how common this txn type is (0.0 = never used, 1.0 = always)
 *   [2] TPS ratio: currentHourlyCount / ewmaHourlyTps
 *   [3] Hourly amount ratio: currentHourlyAmount / ewmaHourlyAmount
 *   [4] Type amount Z-score: (amount - avgAmountForType) / stdDevForType
 *   [5] Hour-of-day factor: normalized hour (0-23) / 24.0
 */
public class FeatureExtractor {

    public static final int FEATURE_COUNT = 6;

    public static final String[] FEATURE_NAMES = {
            "Amount Z-score",
            "Type Frequency",
            "TPS Ratio",
            "Hourly Amount Ratio",
            "Type Amount Z-score",
            "Hour-of-Day"
    };

    public static double[] extract(Transaction txn, ClientProfile profile, EvaluationContext context) {
        double[] features = new double[FEATURE_COUNT];

        // [0] Amount Z-score
        double amountStdDev = profile.getAmountStdDev();
        if (amountStdDev > 0) {
            features[0] = (txn.getAmount() - profile.getEwmaAmount()) / amountStdDev;
        } else {
            features[0] = 0.0;
        }

        // [1] Type frequency (inverted: rare type = high value)
        double typeFreq = profile.getTypeFrequency(txn.getTxnType());
        features[1] = 1.0 - typeFreq; // 1.0 = never used, 0.0 = most common

        // [2] TPS ratio
        if (profile.getEwmaHourlyTps() > 0 && context != null) {
            features[2] = context.getCurrentHourlyTxnCount() / profile.getEwmaHourlyTps();
        } else {
            features[2] = 1.0; // neutral
        }

        // [3] Hourly amount ratio
        if (profile.getEwmaHourlyAmount() > 0 && context != null) {
            double hourlyAmountRupees = context.getCurrentHourlyAmountPaise() / 100.0;
            features[3] = hourlyAmountRupees / profile.getEwmaHourlyAmount();
        } else {
            features[3] = 1.0; // neutral
        }

        // [4] Type amount Z-score
        double typeStdDev = profile.getAmountStdDevForType(txn.getTxnType());
        double typeAvg = profile.getAvgAmountByType().getOrDefault(txn.getTxnType(), 0.0);
        if (typeStdDev > 0) {
            features[4] = (txn.getAmount() - typeAvg) / typeStdDev;
        } else {
            features[4] = 0.0;
        }

        // [5] Hour-of-day (normalized 0.0 to 1.0)
        int hour = Instant.ofEpochMilli(txn.getTimestamp())
                .atZone(ZoneId.systemDefault())
                .getHour();
        features[5] = hour / 24.0;

        return features;
    }

    /**
     * Extract features for training — uses simulated context values based on profile averages.
     * During training, we don't have live hourly counters, so we use profile EWMA values
     * with some noise to represent the typical range.
     */
    public static double[] extractForTraining(Transaction txn, ClientProfile profile, double tpsJitter, double amtJitter) {
        double[] features = new double[FEATURE_COUNT];

        // [0] Amount Z-score
        double amountStdDev = profile.getAmountStdDev();
        features[0] = amountStdDev > 0 ? (txn.getAmount() - profile.getEwmaAmount()) / amountStdDev : 0.0;

        // [1] Type frequency (inverted)
        features[1] = 1.0 - profile.getTypeFrequency(txn.getTxnType());

        // [2] TPS ratio — simulate with EWMA ± jitter
        features[2] = 1.0 + tpsJitter;

        // [3] Hourly amount ratio — simulate with EWMA ± jitter
        features[3] = 1.0 + amtJitter;

        // [4] Type amount Z-score
        double typeStdDev = profile.getAmountStdDevForType(txn.getTxnType());
        double typeAvg = profile.getAvgAmountByType().getOrDefault(txn.getTxnType(), 0.0);
        features[4] = typeStdDev > 0 ? (txn.getAmount() - typeAvg) / typeStdDev : 0.0;

        // [5] Hour-of-day
        int hour = Instant.ofEpochMilli(txn.getTimestamp())
                .atZone(ZoneId.systemDefault())
                .getHour();
        features[5] = hour / 24.0;

        return features;
    }
}
