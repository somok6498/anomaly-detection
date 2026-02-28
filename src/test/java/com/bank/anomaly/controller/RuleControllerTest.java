package com.bank.anomaly.controller;

import com.bank.anomaly.model.AnomalyRule;
import com.bank.anomaly.model.RuleType;
import com.bank.anomaly.service.RuleService;
import com.bank.anomaly.testutil.TestDataFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RuleController.class)
class RuleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RuleService ruleService;

    @Test
    void listRules_success() throws Exception {
        when(ruleService.getAllRules()).thenReturn(List.of(
                TestDataFactory.createAnomalyRule("R1", "Amount Anomaly", RuleType.AMOUNT_ANOMALY, true)));

        mockMvc.perform(get("/api/v1/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].ruleId").value("R1"));
    }

    @Test
    void getRule_found() throws Exception {
        when(ruleService.getRule("R1"))
                .thenReturn(TestDataFactory.createAnomalyRule("R1", "Amount Anomaly", RuleType.AMOUNT_ANOMALY, true));

        mockMvc.perform(get("/api/v1/rules/R1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleId").value("R1"))
                .andExpect(jsonPath("$.name").value("Amount Anomaly"));
    }

    @Test
    void getRule_notFound() throws Exception {
        when(ruleService.getRule("MISSING")).thenReturn(null);

        mockMvc.perform(get("/api/v1/rules/MISSING"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createRule_success() throws Exception {
        AnomalyRule rule = TestDataFactory.createAnomalyRule("R-NEW", "New Rule", RuleType.AMOUNT_ANOMALY, true);
        when(ruleService.createRule(any(AnomalyRule.class))).thenReturn(rule);

        mockMvc.perform(post("/api/v1/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rule)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleId").value("R-NEW"));
    }

    @Test
    void createRule_badRequest_missingName() throws Exception {
        AnomalyRule rule = AnomalyRule.builder().ruleType(RuleType.AMOUNT_ANOMALY).build(); // no name

        mockMvc.perform(post("/api/v1/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rule)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateRule_success() throws Exception {
        AnomalyRule updated = TestDataFactory.createAnomalyRule("R1", "Updated", RuleType.AMOUNT_ANOMALY, true);
        when(ruleService.updateRule(eq("R1"), any(AnomalyRule.class))).thenReturn(updated);

        mockMvc.perform(put("/api/v1/rules/R1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated"));
    }

    @Test
    void updateRule_notFound() throws Exception {
        when(ruleService.updateRule(eq("MISSING"), any(AnomalyRule.class))).thenReturn(null);

        mockMvc.perform(put("/api/v1/rules/MISSING")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                TestDataFactory.createAnomalyRule("MISSING", "X", RuleType.AMOUNT_ANOMALY, true))))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteRule_success() throws Exception {
        when(ruleService.deleteRule("R1")).thenReturn(true);

        mockMvc.perform(delete("/api/v1/rules/R1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteRule_notFound() throws Exception {
        when(ruleService.deleteRule("MISSING")).thenReturn(false);

        mockMvc.perform(delete("/api/v1/rules/MISSING"))
                .andExpect(status().isNotFound());
    }
}
