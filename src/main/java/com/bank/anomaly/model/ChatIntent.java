package com.bank.anomaly.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatIntent {
    private String queryType;
    private ChatFilters filters;
    private String groupBy;
    private Integer limit;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChatFilters {
        private String txnType;
        private Integer timeRangeMinutes;
        private String clientId;
        private String riskLevel;
        private String action;
        private String feedbackStatus;
    }
}
