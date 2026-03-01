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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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
        when(analyticsService.getRulePerformanceStats(any(), any()))
                .thenReturn(List.of(TestDataFactory.createRulePerformance("R1", "Amount Anomaly", 10, 2)));

        mockMvc.perform(get("/api/v1/analytics/rules/performance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].ruleId").value("R1"))
                .andExpect(jsonPath("$[0].tpCount").value(10))
                .andExpect(jsonPath("$[0].fpCount").value(2));

        verify(analyticsService).getRulePerformanceStats(null, null);
    }

    @Test
    void getRulePerformance_withTimeRange_success() throws Exception {
        when(analyticsService.getRulePerformanceStats(1000L, 2000L))
                .thenReturn(List.of(TestDataFactory.createRulePerformance("R2", "Type Anomaly", 5, 1)));

        mockMvc.perform(get("/api/v1/analytics/rules/performance")
                        .param("fromDate", "1000")
                        .param("toDate", "2000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ruleId").value("R2"))
                .andExpect(jsonPath("$[0].tpCount").value(5));

        verify(analyticsService).getRulePerformanceStats(1000L, 2000L);
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
