package com.bank.anomaly.controller;

import com.bank.anomaly.config.AerospikeConfig;
import com.bank.anomaly.config.FeedbackConfig;
import com.bank.anomaly.config.OllamaConfig;
import com.bank.anomaly.config.RiskThresholdConfig;
import com.bank.anomaly.config.TwilioNotificationConfig;
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
    private final TwilioNotificationConfig twilioConfig;
    private final OllamaConfig ollamaConfig;

    public ConfigController(RiskThresholdConfig thresholdConfig,
                            FeedbackConfig feedbackConfig,
                            AerospikeConfig aerospikeConfig,
                            TwilioNotificationConfig twilioConfig,
                            OllamaConfig ollamaConfig) {
        this.thresholdConfig = thresholdConfig;
        this.feedbackConfig = feedbackConfig;
        this.aerospikeConfig = aerospikeConfig;
        this.twilioConfig = twilioConfig;
        this.ollamaConfig = ollamaConfig;
    }

    // ── Thresholds ──

    @Operation(summary = "Get global risk thresholds")
    @GetMapping("/thresholds")
    public ResponseEntity<Map<String, Object>> getThresholds() {
        return ResponseEntity.ok(Map.of(
                "alertThreshold", thresholdConfig.getAlertThreshold(),
                "blockThreshold", thresholdConfig.getBlockThreshold(),
                "ewmaAlpha", thresholdConfig.getEwmaAlpha(),
                "minProfileTxns", thresholdConfig.getMinProfileTxns(),
                "breadthMultiplierPct", thresholdConfig.getBreadthMultiplierPct()
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
        double breadth = toDouble(body, "breadthMultiplierPct", thresholdConfig.getBreadthMultiplierPct());

        if (alert < 0) return badRequest("alertThreshold must be >= 0", "alertThreshold");
        if (block < 0) return badRequest("blockThreshold must be >= 0", "blockThreshold");
        if (alert >= block) return badRequest("alertThreshold must be less than blockThreshold", "alertThreshold");
        if (ewma <= 0 || ewma > 1) return badRequest("ewmaAlpha must be in (0, 1]", "ewmaAlpha");
        if (minTxns < 0) return badRequest("minProfileTxns must be >= 0", "minProfileTxns");
        if (breadth < 0 || breadth > 1) return badRequest("breadthMultiplierPct must be in [0, 1]", "breadthMultiplierPct");

        thresholdConfig.setAlertThreshold(alert);
        thresholdConfig.setBlockThreshold(block);
        thresholdConfig.setEwmaAlpha(ewma);
        thresholdConfig.setMinProfileTxns(minTxns);
        thresholdConfig.setBreadthMultiplierPct(breadth);

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

    // ── Silence Detection ──

    @Operation(summary = "Get silence detection configuration")
    @GetMapping("/silence")
    public ResponseEntity<Map<String, Object>> getSilenceConfig() {
        RiskThresholdConfig.SilenceDetection sd = thresholdConfig.getSilenceDetection();
        return ResponseEntity.ok(Map.of(
                "enabled", sd.isEnabled(),
                "checkIntervalMinutes", sd.getCheckIntervalMinutes(),
                "silenceMultiplier", sd.getSilenceMultiplier(),
                "minExpectedTps", sd.getMinExpectedTps(),
                "minCompletedHours", sd.getMinCompletedHours()
        ));
    }

    @Operation(summary = "Update silence detection configuration",
            description = "Changes apply immediately but reset on restart.")
    @PutMapping("/silence")
    public ResponseEntity<?> updateSilenceConfig(@RequestBody Map<String, Object> body) {
        RiskThresholdConfig.SilenceDetection sd = thresholdConfig.getSilenceDetection();

        boolean enabled = body.containsKey("enabled")
                ? Boolean.parseBoolean(body.get("enabled").toString())
                : sd.isEnabled();
        int interval = toInt(body, "checkIntervalMinutes", sd.getCheckIntervalMinutes());
        double multiplier = toDouble(body, "silenceMultiplier", sd.getSilenceMultiplier());
        double minTps = toDouble(body, "minExpectedTps", sd.getMinExpectedTps());
        long minHours = toLong(body, "minCompletedHours", sd.getMinCompletedHours());

        if (interval <= 0) return badRequest("checkIntervalMinutes must be > 0", "checkIntervalMinutes");
        if (multiplier <= 0) return badRequest("silenceMultiplier must be > 0", "silenceMultiplier");
        if (minTps < 0) return badRequest("minExpectedTps must be >= 0", "minExpectedTps");
        if (minHours < 0) return badRequest("minCompletedHours must be >= 0", "minCompletedHours");

        sd.setEnabled(enabled);
        sd.setCheckIntervalMinutes(interval);
        sd.setSilenceMultiplier(multiplier);
        sd.setMinExpectedTps(minTps);
        sd.setMinCompletedHours(minHours);

        return getSilenceConfig();
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

    // ── Twilio Notification ──

    @Operation(summary = "Get Twilio notification configuration",
            description = "Auth token is masked for security.")
    @GetMapping("/twilio")
    public ResponseEntity<Map<String, Object>> getTwilioConfig() {
        String maskedToken = "";
        if (twilioConfig.getAuthToken() != null && !twilioConfig.getAuthToken().isEmpty()) {
            String token = twilioConfig.getAuthToken();
            maskedToken = token.length() > 4
                    ? "****" + token.substring(token.length() - 4)
                    : "****";
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accountSid", twilioConfig.getAccountSid() != null ? twilioConfig.getAccountSid() : "");
        result.put("authToken", maskedToken);
        result.put("fromNumber", twilioConfig.getFromNumber() != null ? twilioConfig.getFromNumber() : "");
        result.put("toNumber", twilioConfig.getToNumber() != null ? twilioConfig.getToNumber() : "");
        result.put("enabled", twilioConfig.isEnabled());
        result.put("channel", twilioConfig.getChannel() != null ? twilioConfig.getChannel() : "sms");
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Update Twilio notification configuration",
            description = "Changes apply immediately but reset on restart. " +
                    "Omit authToken to keep the current value; send a non-masked value to change it.")
    @PutMapping("/twilio")
    public ResponseEntity<?> updateTwilioConfig(@RequestBody Map<String, Object> body) {
        if (body.containsKey("accountSid")) {
            twilioConfig.setAccountSid(body.get("accountSid").toString());
        }
        if (body.containsKey("authToken")) {
            String token = body.get("authToken").toString();
            // Don't overwrite with the masked value
            if (!token.startsWith("****")) {
                twilioConfig.setAuthToken(token);
            }
        }
        if (body.containsKey("fromNumber")) {
            twilioConfig.setFromNumber(body.get("fromNumber").toString());
        }
        if (body.containsKey("toNumber")) {
            twilioConfig.setToNumber(body.get("toNumber").toString());
        }
        if (body.containsKey("enabled")) {
            twilioConfig.setEnabled(Boolean.parseBoolean(body.get("enabled").toString()));
        }
        if (body.containsKey("channel")) {
            String channel = body.get("channel").toString().toLowerCase();
            if (!channel.equals("sms") && !channel.equals("whatsapp")) {
                return badRequest("channel must be 'sms' or 'whatsapp'", "channel");
            }
            twilioConfig.setChannel(channel);
        }
        return getTwilioConfig();
    }

    // ── Ollama / LLM ──

    @Operation(summary = "Get Ollama LLM configuration")
    @GetMapping("/ollama")
    public ResponseEntity<Map<String, Object>> getOllamaConfig() {
        return ResponseEntity.ok(Map.of(
                "host", ollamaConfig.getHost(),
                "model", ollamaConfig.getModel(),
                "timeoutSeconds", ollamaConfig.getTimeoutSeconds()
        ));
    }

    @Operation(summary = "Update Ollama LLM configuration",
            description = "Changes apply immediately but reset on restart.")
    @PutMapping("/ollama")
    public ResponseEntity<?> updateOllamaConfig(@RequestBody Map<String, Object> body) {
        if (body.containsKey("host")) {
            String host = body.get("host").toString().trim();
            if (host.isEmpty()) return badRequest("host must not be empty", "host");
            ollamaConfig.setHost(host);
        }
        if (body.containsKey("model")) {
            String model = body.get("model").toString().trim();
            if (model.isEmpty()) return badRequest("model must not be empty", "model");
            ollamaConfig.setModel(model);
        }
        if (body.containsKey("timeoutSeconds")) {
            int timeout = toInt(body, "timeoutSeconds", ollamaConfig.getTimeoutSeconds());
            if (timeout <= 0) return badRequest("timeoutSeconds must be > 0", "timeoutSeconds");
            ollamaConfig.setTimeoutSeconds(timeout);
        }
        return getOllamaConfig();
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
