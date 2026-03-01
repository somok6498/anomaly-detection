package com.bank.anomaly.controller;

import com.bank.anomaly.config.AerospikeConfig;
import com.bank.anomaly.config.FeedbackConfig;
import com.bank.anomaly.config.RiskThresholdConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/config")
@Tag(name = "Config", description = "View and modify runtime configuration (thresholds, feedback, transaction types)")
public class ConfigController {

    private final RiskThresholdConfig thresholdConfig;
    private final FeedbackConfig feedbackConfig;
    private final AerospikeConfig aerospikeConfig;

    public ConfigController(RiskThresholdConfig thresholdConfig,
                            FeedbackConfig feedbackConfig,
                            AerospikeConfig aerospikeConfig) {
        this.thresholdConfig = thresholdConfig;
        this.feedbackConfig = feedbackConfig;
        this.aerospikeConfig = aerospikeConfig;
    }

    // ── Thresholds ──

    @Operation(summary = "Get global risk thresholds")
    @GetMapping("/thresholds")
    public ResponseEntity<Map<String, Object>> getThresholds() {
        return ResponseEntity.ok(Map.of(
                "alertThreshold", thresholdConfig.getAlertThreshold(),
                "blockThreshold", thresholdConfig.getBlockThreshold(),
                "ewmaAlpha", thresholdConfig.getEwmaAlpha(),
                "minProfileTxns", thresholdConfig.getMinProfileTxns()
        ));
    }

    @Operation(summary = "Update global risk thresholds",
            description = "Changes apply immediately but reset on restart.")
    @PutMapping("/thresholds")
    public ResponseEntity<?> updateThresholds(@RequestBody Map<String, Object> body) {
        double alert = toDouble(body, "alertThreshold", thresholdConfig.getAlertThreshold());
        double block = toDouble(body, "blockThreshold", thresholdConfig.getBlockThreshold());
        double ewma = toDouble(body, "ewmaAlpha", thresholdConfig.getEwmaAlpha());
        long minTxns = toLong(body, "minProfileTxns", thresholdConfig.getMinProfileTxns());

        if (alert < 0) return badRequest("alertThreshold must be >= 0", "alertThreshold");
        if (block < 0) return badRequest("blockThreshold must be >= 0", "blockThreshold");
        if (alert >= block) return badRequest("alertThreshold must be less than blockThreshold", "alertThreshold");
        if (ewma <= 0 || ewma > 1) return badRequest("ewmaAlpha must be in (0, 1]", "ewmaAlpha");
        if (minTxns < 0) return badRequest("minProfileTxns must be >= 0", "minProfileTxns");

        thresholdConfig.setAlertThreshold(alert);
        thresholdConfig.setBlockThreshold(block);
        thresholdConfig.setEwmaAlpha(ewma);
        thresholdConfig.setMinProfileTxns(minTxns);

        return getThresholds();
    }

    // ── Feedback Config ──

    @Operation(summary = "Get feedback and auto-tuning configuration")
    @GetMapping("/feedback")
    public ResponseEntity<Map<String, Object>> getFeedbackConfig() {
        return ResponseEntity.ok(Map.of(
                "autoAcceptTimeoutMs", feedbackConfig.getAutoAcceptTimeoutMs(),
                "tuningIntervalHours", feedbackConfig.getTuningIntervalHours(),
                "minSamplesForTuning", feedbackConfig.getMinSamplesForTuning(),
                "weightFloor", feedbackConfig.getWeightFloor(),
                "weightCeiling", feedbackConfig.getWeightCeiling(),
                "maxAdjustmentPct", feedbackConfig.getMaxAdjustmentPct()
        ));
    }

    @Operation(summary = "Update feedback and auto-tuning configuration",
            description = "Changes apply immediately but reset on restart.")
    @PutMapping("/feedback")
    public ResponseEntity<?> updateFeedbackConfig(@RequestBody Map<String, Object> body) {
        long timeout = toLong(body, "autoAcceptTimeoutMs", feedbackConfig.getAutoAcceptTimeoutMs());
        int tuningHrs = toInt(body, "tuningIntervalHours", feedbackConfig.getTuningIntervalHours());
        int minSamples = toInt(body, "minSamplesForTuning", feedbackConfig.getMinSamplesForTuning());
        double floor = toDouble(body, "weightFloor", feedbackConfig.getWeightFloor());
        double ceiling = toDouble(body, "weightCeiling", feedbackConfig.getWeightCeiling());
        double maxAdj = toDouble(body, "maxAdjustmentPct", feedbackConfig.getMaxAdjustmentPct());

        if (timeout <= 0) return badRequest("autoAcceptTimeoutMs must be > 0", "autoAcceptTimeoutMs");
        if (tuningHrs <= 0) return badRequest("tuningIntervalHours must be > 0", "tuningIntervalHours");
        if (minSamples <= 0) return badRequest("minSamplesForTuning must be > 0", "minSamplesForTuning");
        if (floor < 0) return badRequest("weightFloor must be >= 0", "weightFloor");
        if (ceiling <= floor) return badRequest("weightCeiling must be greater than weightFloor", "weightCeiling");
        if (maxAdj <= 0 || maxAdj > 1) return badRequest("maxAdjustmentPct must be in (0, 1]", "maxAdjustmentPct");

        feedbackConfig.setAutoAcceptTimeoutMs(timeout);
        feedbackConfig.setTuningIntervalHours(tuningHrs);
        feedbackConfig.setMinSamplesForTuning(minSamples);
        feedbackConfig.setWeightFloor(floor);
        feedbackConfig.setWeightCeiling(ceiling);
        feedbackConfig.setMaxAdjustmentPct(maxAdj);

        return getFeedbackConfig();
    }

    // ── Transaction Types ──

    @Operation(summary = "Get configured transaction types")
    @GetMapping("/transaction-types")
    public ResponseEntity<Map<String, Object>> getTransactionTypes() {
        return ResponseEntity.ok(Map.of(
                "transactionTypes", thresholdConfig.getTransactionTypes()
        ));
    }

    @Operation(summary = "Update configured transaction types",
            description = "Replaces the transaction types list. Types are uppercased and deduplicated.")
    @PutMapping("/transaction-types")
    public ResponseEntity<?> updateTransactionTypes(@RequestBody Map<String, Object> body) {
        Object raw = body.get("transactionTypes");
        if (!(raw instanceof List<?> rawList) || rawList.isEmpty()) {
            return badRequest("transactionTypes must be a non-empty list", "transactionTypes");
        }

        List<String> types = rawList.stream()
                .map(Object::toString)
                .map(String::toUpperCase)
                .distinct()
                .collect(Collectors.toList());

        if (types.isEmpty()) {
            return badRequest("transactionTypes must be a non-empty list", "transactionTypes");
        }

        thresholdConfig.setTransactionTypes(new ArrayList<>(types));
        return getTransactionTypes();
    }

    // ── Aerospike (read-only) ──

    @Operation(summary = "Get Aerospike connection info (read-only)")
    @GetMapping("/aerospike")
    public ResponseEntity<Map<String, Object>> getAerospikeInfo() {
        return ResponseEntity.ok(Map.of(
                "host", aerospikeConfig.getHost(),
                "port", aerospikeConfig.getPort(),
                "namespace", aerospikeConfig.getNamespace()
        ));
    }

    // ── Helpers ──

    private ResponseEntity<Map<String, String>> badRequest(String error, String field) {
        return ResponseEntity.badRequest().body(Map.of("error", error, "field", field));
    }

    private double toDouble(Map<String, Object> body, String key, double defaultVal) {
        Object v = body.get(key);
        if (v == null) return defaultVal;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (NumberFormatException e) { return defaultVal; }
    }

    private long toLong(Map<String, Object> body, String key, long defaultVal) {
        Object v = body.get(key);
        if (v == null) return defaultVal;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); } catch (NumberFormatException e) { return defaultVal; }
    }

    private int toInt(Map<String, Object> body, String key, int defaultVal) {
        Object v = body.get(key);
        if (v == null) return defaultVal;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return defaultVal; }
    }
}
