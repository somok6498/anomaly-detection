package com.bank.anomaly.controller;

import com.bank.anomaly.service.BeneficiaryGraphService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Set;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(GraphController.class)
class GraphControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BeneficiaryGraphService graphService;

    @Test
    void getGraphStatus_success() throws Exception {
        when(graphService.isGraphReady()).thenReturn(true);
        when(graphService.getTotalBeneficiaryKeys()).thenReturn(150);
        when(graphService.getTotalClientCount()).thenReturn(10);
        when(graphService.getLastRefreshTime()).thenReturn(Instant.now());

        mockMvc.perform(get("/api/v1/graph/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isReady").value(true))
                .andExpect(jsonPath("$.totalBeneficiaries").value(150))
                .andExpect(jsonPath("$.totalClients").value(10));
    }

    @Test
    void getBeneficiaryFanIn_success() throws Exception {
        when(graphService.getOtherSenders("HDFC0001234:1234567890", ""))
                .thenReturn(Set.of("C-1", "C-2", "C-3"));
        when(graphService.getFanInCount("HDFC0001234:1234567890")).thenReturn(3);

        mockMvc.perform(get("/api/v1/graph/beneficiary/HDFC0001234/1234567890"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.beneficiaryKey").value("HDFC0001234:1234567890"))
                .andExpect(jsonPath("$.fanInCount").value(3))
                .andExpect(jsonPath("$.senders").isArray());
    }

    @Test
    void getClientGraphMetrics_success() throws Exception {
        when(graphService.getTotalBeneficiaryCount("C-1")).thenReturn(20);
        when(graphService.getSharedBeneficiaryCount("C-1")).thenReturn(5);
        when(graphService.getNetworkDensity("C-1")).thenReturn(0.25);

        mockMvc.perform(get("/api/v1/graph/client/C-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").value("C-1"))
                .andExpect(jsonPath("$.totalBeneficiaries").value(20))
                .andExpect(jsonPath("$.sharedBeneficiaries").value(5))
                .andExpect(jsonPath("$.networkDensity").value(0.25));
    }
}
