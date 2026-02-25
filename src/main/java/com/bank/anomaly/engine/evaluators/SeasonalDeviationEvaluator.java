package com.bank.anomaly.engine.evaluators;

import com.bank.anomaly.config.RiskThresholdConfig;
import com.bank.anomaly.engine.EvaluationContext;
import com.bank.anomaly.engine.RuleEvaluator;
import com.bank.anomaly.model.AnomalyRule;
import com.bank.anomaly.model.ClientProfile;
import com.bank.anomaly.model.RuleResult;
import com.bank.anomaly.model.RuleType;
import com.bank.anomaly.model.Transaction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects seasonal deviations by comparing current metrics against
 * time-slot-specific baselines (hour-of-day for hourly metrics,
 * day-of-week for daily metrics).
 *
 * Falls back to global EWMA baselines when the seasonal slot has
 * insufficient samples (< minSeasonalSamples).
 *
 * Checks 4 metrics: hourly TPS, hourly amount, daily amount, daily TPS.
 * Triggers if ANY metric exceeds its seasonal threshold.
 * Score = max deviation across all triggered metrics.
 */
@Component
public class SeasonalDeviationEvaluator implements RuleEvaluator {

    private final RiskThresholdConfig config;

    public SeasonalDeviationEvaluator(RiskThresholdConfig config) {
        this.config = config;
    }

    @Override
    public RuleType getSupportedRuleType() {
        return RuleType.SEASONAL_DEVIATION;
    }

    @Override
    public RuleResult evaluate(Transaction txn, ClientProfile profile, AnomalyRule rule, EvaluationContext context) {
        double variancePct = rule.getVariancePct() > 0
                ? rule.getVariancePct()
                : config.getRuleDefaults().getSeasonalDeviationVariancePct();

        int minSamples = config.getRuleDefaults().getSeasonalMinSamples();
        if (rule.getParams() != null && rule.getParams().containsKey("minSeasonalSamples")) {
            minSamples = Integer.parseInt(rule.getParams().get("minSeasonalSamples"));
        }

        String hourSlot = context.getCurrentHourOfDaySlot();
        String daySlot = context.getCurrentDayOfWeekSlot();

        List<String> triggeredReasons = new ArrayList<>();
        double maxDeviation = 0.0;

        // 1. Hourly TPS
        double hourlyTpsBaseline = getSeasonalOrGlobalBaseline(
                profile.getSeasonalHourlyTps().getOrDefault(hourSlot, 0.0),
                profile.getSeasonalHourlyTpsCnt().getOrDefault(hourSlot, 0L),
                profile.getEwmaHourlyTps(),
                profile.getCompletedHoursCount(),
                minSamples);

        if (hourlyTpsBaseline > 0) {
            double threshold = hourlyTpsBaseline * (1.0 + variancePct / 100.0);
            long currentTps = context.getCurrentHourlyTxnCount();
            if (currentTps > threshold) {
                double excess = currentTps - threshold;
                double allowedRange = hourlyTpsBaseline * (variancePct / 100.0);
                double dev = allowedRange > 0 ? (excess / allowedRange) * 100.0 : 100.0;
                maxDeviation = Math.max(maxDeviation, dev);
                boolean usedSeasonal = profile.getSeasonalHourlyTpsCnt().getOrDefault(hourSlot, 0L) >= minSamples;
                triggeredReasons.add(String.format(
                        "Hourly TPS: current=%d, %s baseline=%.1f (slot %s), threshold=%.1f, deviation=%.1f%%",
                        currentTps, usedSeasonal ? "seasonal" : "global", hourlyTpsBaseline, hourSlot, threshold, dev));
            }
        }

        // 2. Hourly Amount
        double hourlyAmtBaseline = getSeasonalOrGlobalBaseline(
                profile.getSeasonalHourlyAmt().getOrDefault(hourSlot, 0.0),
                profile.getSeasonalHourlyAmtCnt().getOrDefault(hourSlot, 0L),
                profile.getEwmaHourlyAmount(),
                profile.getCompletedHoursCount(),
                minSamples);

        if (hourlyAmtBaseline > 0) {
            double threshold = hourlyAmtBaseline * (1.0 + variancePct / 100.0);
            double currentAmt = context.getCurrentHourlyAmountPaise() / 100.0;
            if (currentAmt > threshold) {
                double excess = currentAmt - threshold;
                double allowedRange = hourlyAmtBaseline * (variancePct / 100.0);
                double dev = allowedRange > 0 ? (excess / allowedRange) * 100.0 : 100.0;
                maxDeviation = Math.max(maxDeviation, dev);
                boolean usedSeasonal = profile.getSeasonalHourlyAmtCnt().getOrDefault(hourSlot, 0L) >= minSamples;
                triggeredReasons.add(String.format(
                        "Hourly Amount: current=%.0f, %s baseline=%.0f (slot %s), threshold=%.0f, deviation=%.1f%%",
                        currentAmt, usedSeasonal ? "seasonal" : "global", hourlyAmtBaseline, hourSlot, threshold, dev));
            }
        }

        // 3. Daily Amount
        double dailyAmtBaseline = getSeasonalOrGlobalBaseline(
                profile.getSeasonalDailyAmt().getOrDefault(daySlot, 0.0),
                profile.getSeasonalDailyAmtCnt().getOrDefault(daySlot, 0L),
                profile.getEwmaDailyAmount(),
                profile.getCompletedDaysCount(),
                minSamples);

        if (dailyAmtBaseline > 0) {
            double threshold = dailyAmtBaseline * (1.0 + variancePct / 100.0);
            double currentAmt = context.getCurrentDailyAmountPaise() / 100.0;
            if (currentAmt > threshold) {
                double excess = currentAmt - threshold;
                double allowedRange = dailyAmtBaseline * (variancePct / 100.0);
                double dev = allowedRange > 0 ? (excess / allowedRange) * 100.0 : 100.0;
                maxDeviation = Math.max(maxDeviation, dev);
                boolean usedSeasonal = profile.getSeasonalDailyAmtCnt().getOrDefault(daySlot, 0L) >= minSamples;
                triggeredReasons.add(String.format(
                        "Daily Amount: current=%.0f, %s baseline=%.0f (day %s), threshold=%.0f, deviation=%.1f%%",
                        currentAmt, usedSeasonal ? "seasonal" : "global", dailyAmtBaseline, daySlot, threshold, dev));
            }
        }

        // 4. Daily TPS
        double dailyTpsBaseline = getSeasonalOrGlobalBaseline(
                profile.getSeasonalDailyTps().getOrDefault(daySlot, 0.0),
                profile.getSeasonalDailyTpsCnt().getOrDefault(daySlot, 0L),
                0.0, // no global daily TPS EWMA exists — skip if seasonal insufficient
                0,
                minSamples);

        if (dailyTpsBaseline > 0) {
            double threshold = dailyTpsBaseline * (1.0 + variancePct / 100.0);
            long currentTps = context.getCurrentDailyTxnCount();
            if (currentTps > threshold) {
                double excess = currentTps - threshold;
                double allowedRange = dailyTpsBaseline * (variancePct / 100.0);
                double dev = allowedRange > 0 ? (excess / allowedRange) * 100.0 : 100.0;
                maxDeviation = Math.max(maxDeviation, dev);
                triggeredReasons.add(String.format(
                        "Daily TPS: current=%d, seasonal baseline=%.1f (day %s), threshold=%.1f, deviation=%.1f%%",
                        currentTps, dailyTpsBaseline, daySlot, threshold, dev));
            }
        }

        if (triggeredReasons.isEmpty()) {
            return notTriggered(rule);
        }

        double partialScore = Math.min(100.0, maxDeviation);
        String reason = "Seasonal deviation: " + String.join("; ", triggeredReasons);

        return RuleResult.builder()
                .ruleId(rule.getRuleId())
                .ruleName(rule.getName())
                .ruleType(rule.getRuleType())
                .triggered(true)
                .deviationPct(maxDeviation)
                .partialScore(partialScore)
                .riskWeight(rule.getRiskWeight())
                .reason(reason)
                .build();
    }

    private double getSeasonalOrGlobalBaseline(double seasonalValue, long seasonalCount,
                                                double globalValue, long globalCount,
                                                int minSamples) {
        if (seasonalCount >= minSamples && seasonalValue > 0) {
            return seasonalValue;
        }
        if (globalCount >= 2 && globalValue > 0) {
            return globalValue;
        }
        return 0.0;
    }

    private RuleResult notTriggered(AnomalyRule rule) {
        return RuleResult.builder()
                .ruleId(rule.getRuleId())
                .ruleName(rule.getName())
                .ruleType(rule.getRuleType())
                .triggered(false)
                .deviationPct(0.0)
                .partialScore(0.0)
                .riskWeight(rule.getRiskWeight())
                .reason("Within seasonal baselines")
                .build();
    }
}
