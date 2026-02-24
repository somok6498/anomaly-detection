package com.bank.anomaly.service;

import com.bank.anomaly.config.MetricsConfig;
import com.bank.anomaly.config.RiskThresholdConfig;
import com.bank.anomaly.model.ClientProfile;
import com.bank.anomaly.repository.ClientProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class SilenceDetectionService {

    private static final Logger log = LoggerFactory.getLogger(SilenceDetectionService.class);

    private final ClientProfileRepository profileRepo;
    private final TwilioNotificationService notificationService;
    private final RiskThresholdConfig config;
    private final MetricsConfig metricsConfig;

    // Track which clients have been alerted (clientId → alert timestamp)
    private final ConcurrentHashMap<String, Long> alertedClients = new ConcurrentHashMap<>();

    public SilenceDetectionService(ClientProfileRepository profileRepo,
                                    TwilioNotificationService notificationService,
                                    RiskThresholdConfig config,
                                    MetricsConfig metricsConfig) {
        this.profileRepo = profileRepo;
        this.notificationService = notificationService;
        this.config = config;
        this.metricsConfig = metricsConfig;
    }

    @Scheduled(fixedRateString = "${risk.silence-detection.check-interval-minutes:5}",
               timeUnit = TimeUnit.MINUTES,
               initialDelayString = "1")
    public void checkForSilentClients() {
        if (!config.getSilenceDetection().isEnabled()) {
            return;
        }

        RiskThresholdConfig.SilenceDetection sd = config.getSilenceDetection();
        List<ClientProfile> allProfiles = profileRepo.scanAllProfiles();
        long now = System.currentTimeMillis();
        Set<String> currentlySilent = new HashSet<>();

        int scanned = 0;
        int eligible = 0;

        for (ClientProfile profile : allProfiles) {
            scanned++;

            // Guards: sufficient history + meaningful TPS baseline
            if (profile.getCompletedHoursCount() < sd.getMinCompletedHours()) continue;
            if (profile.getEwmaHourlyTps() < sd.getMinExpectedTps()) continue;
            if (profile.getLastUpdated() <= 0) continue;

            eligible++;

            double silenceMinutes = (now - profile.getLastUpdated()) / 60000.0;
            double expectedGapMinutes = 60.0 / profile.getEwmaHourlyTps();
            double threshold = expectedGapMinutes * sd.getSilenceMultiplier();

            if (silenceMinutes > threshold) {
                currentlySilent.add(profile.getClientId());

                if (!alertedClients.containsKey(profile.getClientId())) {
                    // New silence detected — alert!
                    alertedClients.put(profile.getClientId(), now);
                    metricsConfig.recordSilenceDetected(profile.getClientId());

                    notificationService.notifySilentClient(
                            profile.getClientId(), silenceMinutes,
                            expectedGapMinutes, profile.getEwmaHourlyTps());

                    log.warn("SILENCE DETECTED: {} — no transactions for {} min " +
                             "(expected every {} min, {} txns/hr)",
                             profile.getClientId(),
                             String.format("%.1f", silenceMinutes),
                             String.format("%.1f", expectedGapMinutes),
                             String.format("%.1f", profile.getEwmaHourlyTps()));
                }
            }
        }

        // Clear alerts for clients that resumed transacting
        alertedClients.keySet().removeIf(clientId -> {
            if (!currentlySilent.contains(clientId)) {
                metricsConfig.recordSilenceResolved(clientId);
                log.info("SILENCE RESOLVED: {} — transactions resumed", clientId);
                return true;
            }
            return false;
        });

        // Update gauge
        metricsConfig.updateSilentClientCount(alertedClients.size());

        log.info("Silence check complete: scanned={}, eligible={}, silent={}, alerted={}",
                scanned, eligible, currentlySilent.size(), alertedClients.size());
    }

    /**
     * Returns the map of currently-alerted clients (clientId → alert timestamp).
     */
    public Map<String, Long> getAlertedClients() {
        return Collections.unmodifiableMap(alertedClients);
    }
}
