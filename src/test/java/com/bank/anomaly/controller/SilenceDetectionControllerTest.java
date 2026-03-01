package com.bank.anomaly.controller;

import com.bank.anomaly.config.RiskThresholdConfig;
import com.bank.anomaly.model.ClientProfile;
import com.bank.anomaly.repository.ClientProfileRepository;
import com.bank.anomaly.service.SilenceDetectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SilenceDetectionController.class)
class SilenceDetectionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SilenceDetectionService silenceDetectionService;

    @MockBean
    private ClientProfileRepository profileRepo;

    @MockBean
    private RiskThresholdConfig config;

    @BeforeEach
    void setUp() {
        RiskThresholdConfig.SilenceDetection sd = new RiskThresholdConfig.SilenceDetection();
        sd.setSilenceMultiplier(3.0);
        when(config.getSilenceDetection()).thenReturn(sd);
    }

    @Test
    void getSilentClients_success() throws Exception {
        long alertedAt = System.currentTimeMillis() - 600000; // 10 min ago
        Map<String, Long> alerted = new LinkedHashMap<>();
        alerted.put("C-1", alertedAt);
        when(silenceDetectionService.getAlertedClients()).thenReturn(alerted);

        ClientProfile profile = new ClientProfile();
        profile.setClientId("C-1");
        profile.setEwmaHourlyTps(10.0);
        profile.setLastUpdated(alertedAt);
        profile.setTotalTxnCount(500);
        profile.setEwmaAmount(25000.0);
        when(profileRepo.findByClientId("C-1")).thenReturn(profile);

        mockMvc.perform(get("/api/v1/silence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.silentClientCount").value(1))
                .andExpect(jsonPath("$.clients").isArray())
                .andExpect(jsonPath("$.clients[0].clientId").value("C-1"))
                .andExpect(jsonPath("$.clients[0].ewmaHourlyTps").value(10.0))
                .andExpect(jsonPath("$.clients[0].expectedGapMinutes").value(6.0))
                .andExpect(jsonPath("$.clients[0].thresholdMinutes").value(18.0))
                .andExpect(jsonPath("$.clients[0].totalTxnCount").value(500))
                .andExpect(jsonPath("$.clients[0].ewmaAmount").value(25000.0))
                .andExpect(jsonPath("$.clients[0].silenceRatio").isNumber());
    }

    @Test
    void getSilentClients_noProfileStillReturnsBasicInfo() throws Exception {
        Map<String, Long> alerted = new LinkedHashMap<>();
        alerted.put("C-UNKNOWN", System.currentTimeMillis() - 300000);
        when(silenceDetectionService.getAlertedClients()).thenReturn(alerted);
        when(profileRepo.findByClientId("C-UNKNOWN")).thenReturn(null);

        mockMvc.perform(get("/api/v1/silence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clients[0].clientId").value("C-UNKNOWN"))
                .andExpect(jsonPath("$.clients[0].silentForMinutes").isNumber())
                .andExpect(jsonPath("$.clients[0].ewmaHourlyTps").doesNotExist());
    }

    @Test
    void triggerCheck_success() throws Exception {
        Map<String, Long> alerted = new LinkedHashMap<>();
        when(silenceDetectionService.getAlertedClients()).thenReturn(alerted);

        mockMvc.perform(post("/api/v1/silence/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.silentClientCount").value(0));
    }
}
