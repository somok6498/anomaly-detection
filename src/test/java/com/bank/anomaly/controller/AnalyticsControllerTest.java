package com.bank.anomaly.controller;

import com.bank.anomaly.model.NetworkGraph;
import com.bank.anomaly.model.RulePerformance;
import com.bank.anomaly.repository.AiFeedbackRepository;
import com.bank.anomaly.service.AnalyticsService;
import com.bank.anomaly.testutil.TestDataFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @MockBean
    private AiFeedbackRepository aiFeedbackRepository;

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

    @Test
    void getAiFeedbackStats_success() throws Exception {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("helpful", 15);
        stats.put("notHelpful", 5);
        stats.put("total", 20);
        stats.put("helpfulPct", 75.0);
        when(aiFeedbackRepository.getStats()).thenReturn(stats);

        mockMvc.perform(get("/api/v1/analytics/ai-feedback/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.helpful").value(15))
                .andExpect(jsonPath("$.notHelpful").value(5))
                .andExpect(jsonPath("$.total").value(20))
                .andExpect(jsonPath("$.helpfulPct").value(75.0));
    }
}
