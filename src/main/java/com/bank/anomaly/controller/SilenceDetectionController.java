package com.bank.anomaly.controller;

import com.bank.anomaly.config.RiskThresholdConfig;
import com.bank.anomaly.model.ClientProfile;
import com.bank.anomaly.repository.ClientProfileRepository;
import com.bank.anomaly.service.SilenceDetectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/silence")
@Tag(name = "Silence Detection", description = "Monitor clients whose transaction flow has unexpectedly stopped")
public class SilenceDetectionController {

    private final SilenceDetectionService silenceDetectionService;
    private final ClientProfileRepository profileRepo;
    private final RiskThresholdConfig config;

    public SilenceDetectionController(SilenceDetectionService silenceDetectionService,
                                      ClientProfileRepository profileRepo,
                                      RiskThresholdConfig config) {
        this.silenceDetectionService = silenceDetectionService;
        this.profileRepo = profileRepo;
        this.config = config;
    }

    @GetMapping
    @Operation(summary = "Get currently silent clients",
               description = "Returns clients whose transaction silence exceeds their normal pattern, enriched with profile data")
    public ResponseEntity<Map<String, Object>> getSilentClients() {
        Map<String, Long> alerted = silenceDetectionService.getAlertedClients();
        long now = System.currentTimeMillis();
        double multiplier = config.getSilenceDetection().getSilenceMultiplier();

        List<Map<String, Object>> clients = alerted.entrySet().stream()
                .map(entry -> {
                    String clientId = entry.getKey();
                    long alertedAt = entry.getValue();
                    long silentForMinutes = Math.round((now - alertedAt) / 60000.0);

                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("clientId", clientId);
                    info.put("alertedAt", alertedAt);
                    info.put("silentForMinutes", silentForMinutes);

                    // Enrich with profile data
                    ClientProfile profile = profileRepo.findByClientId(clientId);
                    if (profile != null) {
                        double ewmaTps = profile.getEwmaHourlyTps();
                        double expectedGap = ewmaTps > 0 ? 60.0 / ewmaTps : 0;
                        double threshold = expectedGap * multiplier;
                        double ratio = expectedGap > 0 ? silentForMinutes / expectedGap : 0;

                        info.put("lastTransactionAt", profile.getLastUpdated());
                        info.put("ewmaHourlyTps", Math.round(ewmaTps * 100.0) / 100.0);
                        info.put("expectedGapMinutes", Math.round(expectedGap * 10.0) / 10.0);
                        info.put("silenceMultiplier", multiplier);
                        info.put("thresholdMinutes", Math.round(threshold * 10.0) / 10.0);
                        info.put("silenceRatio", Math.round(ratio * 10.0) / 10.0);
                        info.put("totalTxnCount", profile.getTotalTxnCount());
                        info.put("ewmaAmount", Math.round(profile.getEwmaAmount() * 100.0) / 100.0);
                    }
                    return info;
                })
                .collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("silentClientCount", alerted.size());
        response.put("clients", clients);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/check")
    @Operation(summary = "Trigger silence check manually",
               description = "Runs an immediate silence detection scan (for testing)")
    public ResponseEntity<Map<String, Object>> triggerCheck() {
        silenceDetectionService.checkForSilentClients();

        Map<String, Long> alerted = silenceDetectionService.getAlertedClients();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("silentClientCount", alerted.size());
        response.put("silentClients", alerted.keySet());
        return ResponseEntity.ok(response);
    }
}
