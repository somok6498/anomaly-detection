package com.bank.anomaly.controller;

import com.bank.anomaly.model.NetworkGraph;
import com.bank.anomaly.model.RulePerformance;
import com.bank.anomaly.service.AnalyticsService;
import com.bank.anomaly.testutil.TestDataFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AnalyticsController.class)
class AnalyticsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AnalyticsService analyticsService;

    @Test
    void getRulePerformance_success() throws Exception {
        when(analyticsService.getRulePerformanceStats())
                .thenReturn(List.of(TestDataFactory.createRulePerformance("R1", "Amount Anomaly", 10, 2)));

        mockMvc.perform(get("/api/v1/analytics/rules/performance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].ruleId").value("R1"))
                .andExpect(jsonPath("$[0].tpCount").value(10))
                .andExpect(jsonPath("$[0].fpCount").value(2));
    }

    @Test
    void getClientNetwork_success() throws Exception {
        when(analyticsService.getClientNetwork("CLIENT-007"))
                .thenReturn(TestDataFactory.createNetworkGraph());

        mockMvc.perform(get("/api/v1/analytics/graph/client/CLIENT-007/network"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes").isArray())
                .andExpect(jsonPath("$.edges").isArray())
                .andExpect(jsonPath("$.nodes[0].id").value("CLIENT-007"));
    }
}
