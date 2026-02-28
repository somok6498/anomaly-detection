package com.bank.anomaly.controller;

import com.bank.anomaly.service.SilenceDetectionService;
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

    @Test
    void getSilentClients_success() throws Exception {
        Map<String, Long> alerted = new LinkedHashMap<>();
        alerted.put("C-1", System.currentTimeMillis() - 600000); // 10 min ago
        when(silenceDetectionService.getAlertedClients()).thenReturn(alerted);

        mockMvc.perform(get("/api/v1/silence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.silentClientCount").value(1))
                .andExpect(jsonPath("$.clients").isArray())
                .andExpect(jsonPath("$.clients[0].clientId").value("C-1"));
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
