package com.bank.anomaly.controller;

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

    public SilenceDetectionController(SilenceDetectionService silenceDetectionService) {
        this.silenceDetectionService = silenceDetectionService;
    }

    @GetMapping
    @Operation(summary = "Get currently silent clients",
               description = "Returns clients whose transaction silence exceeds their normal pattern")
    public ResponseEntity<Map<String, Object>> getSilentClients() {
        Map<String, Long> alerted = silenceDetectionService.getAlertedClients();
        long now = System.currentTimeMillis();

        List<Map<String, Object>> clients = alerted.entrySet().stream()
                .map(entry -> {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("clientId", entry.getKey());
                    info.put("alertedAt", entry.getValue());
                    info.put("silentForMinutes", Math.round((now - entry.getValue()) / 60000.0));
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
