package com.bank.anomaly.controller;

import com.bank.anomaly.model.ClientProfile;
import com.bank.anomaly.service.ProfileService;
import com.bank.anomaly.testutil.TestDataFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProfileController.class)
class ProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProfileService profileService;

    @Test
    void getProfile_found() throws Exception {
        when(profileService.getOrCreateProfile("C-1"))
                .thenReturn(TestDataFactory.createClientProfile("C-1", 5000));

        mockMvc.perform(get("/api/v1/profiles/C-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").value("C-1"))
                .andExpect(jsonPath("$.totalTxnCount").value(5000));
    }

    @Test
    void getProfile_notFound_newClient() throws Exception {
        when(profileService.getOrCreateProfile("NEW"))
                .thenReturn(ClientProfile.builder().clientId("NEW").totalTxnCount(0).build());

        mockMvc.perform(get("/api/v1/profiles/NEW"))
                .andExpect(status().isNotFound());
    }
}
