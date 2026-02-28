package com.bank.anomaly.controller;

import com.bank.anomaly.repository.IsolationForestModelRepository;
import com.bank.anomaly.service.IsolationForestTrainingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ModelController.class)
class ModelControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IsolationForestTrainingService trainingService;

    @MockBean
    private IsolationForestModelRepository modelRepository;

    @Test
    void trainForClient_success() throws Exception {
        when(modelRepository.getModelMetadata("C-1"))
                .thenReturn(Map.of("clientId", "C-1", "treeCount", 100, "featureCount", 6));

        mockMvc.perform(post("/api/v1/models/train/C-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").value("C-1"))
                .andExpect(jsonPath("$.treeCount").value(100));
    }

    @Test
    void trainForClient_failure() throws Exception {
        when(modelRepository.getModelMetadata("C-1")).thenReturn(null);

        mockMvc.perform(post("/api/v1/models/train/C-1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void trainAll_success() throws Exception {
        mockMvc.perform(post("/api/v1/models/train"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists());
    }

    @Test
    void getModelMetadata_found() throws Exception {
        when(modelRepository.getModelMetadata("C-1"))
                .thenReturn(Map.of("clientId", "C-1", "treeCount", 100));

        mockMvc.perform(get("/api/v1/models/C-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").value("C-1"));
    }

    @Test
    void getModelMetadata_notFound() throws Exception {
        when(modelRepository.getModelMetadata("MISSING")).thenReturn(null);

        mockMvc.perform(get("/api/v1/models/MISSING"))
                .andExpect(status().isNotFound());
    }
}
