package com.bank.anomaly.controller;

import com.bank.anomaly.config.AerospikeConfig;
import com.bank.anomaly.config.FeedbackConfig;
import com.bank.anomaly.config.OllamaConfig;
import com.bank.anomaly.config.RiskThresholdConfig;
import com.bank.anomaly.config.TwilioNotificationConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ConfigController.class)
class ConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RiskThresholdConfig thresholdConfig;

    @MockBean
    private FeedbackConfig feedbackConfig;

    @MockBean
    private AerospikeConfig aerospikeConfig;

    @MockBean
    private TwilioNotificationConfig twilioConfig;

    @MockBean
    private OllamaConfig ollamaConfig;

    // ── Thresholds ──

    @Test
    void getThresholds_success() throws Exception {
        when(thresholdConfig.getAlertThreshold()).thenReturn(30.0);
        when(thresholdConfig.getBlockThreshold()).thenReturn(70.0);
        when(thresholdConfig.getEwmaAlpha()).thenReturn(0.01);
        when(thresholdConfig.getMinProfileTxns()).thenReturn(20L);

        mockMvc.perform(get("/api/v1/config/thresholds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertThreshold").value(30.0))
                .andExpect(jsonPath("$.blockThreshold").value(70.0))
                .andExpect(jsonPath("$.ewmaAlpha").value(0.01))
                .andExpect(jsonPath("$.minProfileTxns").value(20));
    }

    @Test
    void updateThresholds_success() throws Exception {
        when(thresholdConfig.getAlertThreshold()).thenReturn(25.0);
        when(thresholdConfig.getBlockThreshold()).thenReturn(75.0);
        when(thresholdConfig.getEwmaAlpha()).thenReturn(0.02);
        when(thresholdConfig.getMinProfileTxns()).thenReturn(10L);

        mockMvc.perform(put("/api/v1/config/thresholds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "alertThreshold", 25.0,
                                "blockThreshold", 75.0,
                                "ewmaAlpha", 0.02,
                                "minProfileTxns", 10
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertThreshold").value(25.0));

        verify(thresholdConfig).setAlertThreshold(25.0);
        verify(thresholdConfig).setBlockThreshold(75.0);
    }

    @Test
    void updateThresholds_alertGteBlock_returns400() throws Exception {
        when(thresholdConfig.getAlertThreshold()).thenReturn(30.0);
        when(thresholdConfig.getBlockThreshold()).thenReturn(70.0);

        mockMvc.perform(put("/api/v1/config/thresholds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "alertThreshold", 80.0,
                                "blockThreshold", 70.0
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void updateThresholds_invalidEwma_returns400() throws Exception {
        when(thresholdConfig.getAlertThreshold()).thenReturn(30.0);
        when(thresholdConfig.getBlockThreshold()).thenReturn(70.0);
        when(thresholdConfig.getEwmaAlpha()).thenReturn(0.01);
        when(thresholdConfig.getMinProfileTxns()).thenReturn(20L);

        mockMvc.perform(put("/api/v1/config/thresholds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "ewmaAlpha", 0.0
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("ewmaAlpha"));
    }

    // ── Feedback ──

    @Test
    void getFeedbackConfig_success() throws Exception {
        when(feedbackConfig.getAutoAcceptTimeoutMs()).thenReturn(3600000L);
        when(feedbackConfig.getTuningIntervalHours()).thenReturn(6);
        when(feedbackConfig.getMinSamplesForTuning()).thenReturn(50);
        when(feedbackConfig.getWeightFloor()).thenReturn(0.5);
        when(feedbackConfig.getWeightCeiling()).thenReturn(5.0);
        when(feedbackConfig.getMaxAdjustmentPct()).thenReturn(0.10);

        mockMvc.perform(get("/api/v1/config/feedback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoAcceptTimeoutMs").value(3600000))
                .andExpect(jsonPath("$.weightFloor").value(0.5))
                .andExpect(jsonPath("$.weightCeiling").value(5.0));
    }

    @Test
    void updateFeedbackConfig_success() throws Exception {
        when(feedbackConfig.getAutoAcceptTimeoutMs()).thenReturn(7200000L);
        when(feedbackConfig.getTuningIntervalHours()).thenReturn(12);
        when(feedbackConfig.getMinSamplesForTuning()).thenReturn(100);
        when(feedbackConfig.getWeightFloor()).thenReturn(0.3);
        when(feedbackConfig.getWeightCeiling()).thenReturn(6.0);
        when(feedbackConfig.getMaxAdjustmentPct()).thenReturn(0.15);

        mockMvc.perform(put("/api/v1/config/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "autoAcceptTimeoutMs", 7200000,
                                "weightFloor", 0.3,
                                "weightCeiling", 6.0,
                                "maxAdjustmentPct", 0.15
                        ))))
                .andExpect(status().isOk());

        verify(feedbackConfig).setWeightFloor(0.3);
        verify(feedbackConfig).setWeightCeiling(6.0);
    }

    @Test
    void updateFeedbackConfig_floorGteCeiling_returns400() throws Exception {
        when(feedbackConfig.getAutoAcceptTimeoutMs()).thenReturn(3600000L);
        when(feedbackConfig.getTuningIntervalHours()).thenReturn(6);
        when(feedbackConfig.getMinSamplesForTuning()).thenReturn(50);
        when(feedbackConfig.getWeightFloor()).thenReturn(0.5);
        when(feedbackConfig.getWeightCeiling()).thenReturn(5.0);
        when(feedbackConfig.getMaxAdjustmentPct()).thenReturn(0.10);

        mockMvc.perform(put("/api/v1/config/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "weightFloor", 5.0,
                                "weightCeiling", 3.0
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("weightCeiling"));
    }

    // ── Transaction Types ──

    @Test
    void getTransactionTypes_success() throws Exception {
        when(thresholdConfig.getTransactionTypes())
                .thenReturn(List.of("NEFT", "RTGS", "IMPS", "UPI", "IFT"));

        mockMvc.perform(get("/api/v1/config/transaction-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionTypes").isArray())
                .andExpect(jsonPath("$.transactionTypes[0]").value("NEFT"));
    }

    @Test
    void updateTransactionTypes_success() throws Exception {
        when(thresholdConfig.getTransactionTypes())
                .thenReturn(new ArrayList<>(List.of("NEFT", "RTGS", "UPI")));

        mockMvc.perform(put("/api/v1/config/transaction-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "transactionTypes", List.of("NEFT", "RTGS", "UPI")
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionTypes").isArray());

        verify(thresholdConfig).setTransactionTypes(anyList());
    }

    @Test
    void updateTransactionTypes_emptyList_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/config/transaction-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "transactionTypes", List.of()
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("transactionTypes"));
    }

    // ── Silence Detection ──

    @Test
    void getSilenceConfig_success() throws Exception {
        RiskThresholdConfig.SilenceDetection sd = new RiskThresholdConfig.SilenceDetection();
        sd.setEnabled(true);
        sd.setCheckIntervalMinutes(5);
        sd.setSilenceMultiplier(3.0);
        sd.setMinExpectedTps(1.0);
        sd.setMinCompletedHours(48);
        when(thresholdConfig.getSilenceDetection()).thenReturn(sd);

        mockMvc.perform(get("/api/v1/config/silence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.checkIntervalMinutes").value(5))
                .andExpect(jsonPath("$.silenceMultiplier").value(3.0))
                .andExpect(jsonPath("$.minExpectedTps").value(1.0))
                .andExpect(jsonPath("$.minCompletedHours").value(48));
    }

    @Test
    void updateSilenceConfig_success() throws Exception {
        RiskThresholdConfig.SilenceDetection sd = new RiskThresholdConfig.SilenceDetection();
        sd.setEnabled(false);
        sd.setCheckIntervalMinutes(10);
        sd.setSilenceMultiplier(5.0);
        sd.setMinExpectedTps(2.0);
        sd.setMinCompletedHours(24);
        when(thresholdConfig.getSilenceDetection()).thenReturn(sd);

        mockMvc.perform(put("/api/v1/config/silence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "enabled", false,
                                "checkIntervalMinutes", 10,
                                "silenceMultiplier", 5.0,
                                "minExpectedTps", 2.0,
                                "minCompletedHours", 24
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.silenceMultiplier").value(5.0));
    }

    @Test
    void updateSilenceConfig_invalidMultiplier_returns400() throws Exception {
        RiskThresholdConfig.SilenceDetection sd = new RiskThresholdConfig.SilenceDetection();
        when(thresholdConfig.getSilenceDetection()).thenReturn(sd);

        mockMvc.perform(put("/api/v1/config/silence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "silenceMultiplier", 0
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("silenceMultiplier"));
    }

    // ── Aerospike (read-only) ──

    @Test
    void getAerospikeInfo_success() throws Exception {
        when(aerospikeConfig.getHost()).thenReturn("127.0.0.1");
        when(aerospikeConfig.getPort()).thenReturn(3000);
        when(aerospikeConfig.getNamespace()).thenReturn("banking");

        mockMvc.perform(get("/api/v1/config/aerospike"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.host").value("127.0.0.1"))
                .andExpect(jsonPath("$.port").value(3000))
                .andExpect(jsonPath("$.namespace").value("banking"));
    }

    // ── Twilio ──

    @Test
    void getTwilioConfig_masksAuthToken() throws Exception {
        when(twilioConfig.getAccountSid()).thenReturn("AC123456");
        when(twilioConfig.getAuthToken()).thenReturn("secret_token_abcd");
        when(twilioConfig.getFromNumber()).thenReturn("+14155238886");
        when(twilioConfig.getToNumber()).thenReturn("+919830709527");
        when(twilioConfig.isEnabled()).thenReturn(true);
        when(twilioConfig.getChannel()).thenReturn("whatsapp");

        mockMvc.perform(get("/api/v1/config/twilio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountSid").value("AC123456"))
                .andExpect(jsonPath("$.authToken").value("****abcd"))
                .andExpect(jsonPath("$.fromNumber").value("+14155238886"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.channel").value("whatsapp"));
    }

    @Test
    void updateTwilioConfig_success() throws Exception {
        when(twilioConfig.getAccountSid()).thenReturn("AC123456");
        when(twilioConfig.getAuthToken()).thenReturn("secret_token_abcd");
        when(twilioConfig.getFromNumber()).thenReturn("+14155238886");
        when(twilioConfig.getToNumber()).thenReturn("+919830709527");
        when(twilioConfig.isEnabled()).thenReturn(false);
        when(twilioConfig.getChannel()).thenReturn("sms");

        mockMvc.perform(put("/api/v1/config/twilio")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "enabled", false,
                                "channel", "sms"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.channel").value("sms"));

        verify(twilioConfig).setEnabled(false);
        verify(twilioConfig).setChannel("sms");
    }

    @Test
    void updateTwilioConfig_invalidChannel_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/config/twilio")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "channel", "email"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("channel"));
    }

    @Test
    void updateTwilioConfig_maskedTokenNotOverwritten() throws Exception {
        when(twilioConfig.getAccountSid()).thenReturn("AC123456");
        when(twilioConfig.getAuthToken()).thenReturn("original_secret");
        when(twilioConfig.getFromNumber()).thenReturn("+14155238886");
        when(twilioConfig.getToNumber()).thenReturn("+919830709527");
        when(twilioConfig.isEnabled()).thenReturn(true);
        when(twilioConfig.getChannel()).thenReturn("whatsapp");

        mockMvc.perform(put("/api/v1/config/twilio")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "authToken", "****cret"
                        ))))
                .andExpect(status().isOk());

        // Should NOT overwrite with masked value
        verify(twilioConfig, never()).setAuthToken(anyString());
    }

    // ── Ollama / LLM ──

    @Test
    void getOllamaConfig_success() throws Exception {
        when(ollamaConfig.getHost()).thenReturn("http://localhost:11434");
        when(ollamaConfig.getModel()).thenReturn("llama3.2:1b");
        when(ollamaConfig.getTimeoutSeconds()).thenReturn(300);

        mockMvc.perform(get("/api/v1/config/ollama"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.host").value("http://localhost:11434"))
                .andExpect(jsonPath("$.model").value("llama3.2:1b"))
                .andExpect(jsonPath("$.timeoutSeconds").value(300));
    }

    @Test
    void updateOllamaConfig_success() throws Exception {
        when(ollamaConfig.getHost()).thenReturn("http://newhost:11434");
        when(ollamaConfig.getModel()).thenReturn("llama3:8b");
        when(ollamaConfig.getTimeoutSeconds()).thenReturn(600);

        mockMvc.perform(put("/api/v1/config/ollama")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "host", "http://newhost:11434",
                                "model", "llama3:8b",
                                "timeoutSeconds", 600
                        ))))
                .andExpect(status().isOk());

        verify(ollamaConfig).setHost("http://newhost:11434");
        verify(ollamaConfig).setModel("llama3:8b");
        verify(ollamaConfig).setTimeoutSeconds(600);
    }

    @Test
    void updateOllamaConfig_emptyModel_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/config/ollama")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "model", ""
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("model"));
    }

    @Test
    void updateOllamaConfig_invalidTimeout_returns400() throws Exception {
        when(ollamaConfig.getTimeoutSeconds()).thenReturn(300);

        mockMvc.perform(put("/api/v1/config/ollama")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "timeoutSeconds", 0
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("timeoutSeconds"));
    }
}
