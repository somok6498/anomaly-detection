package com.bank.anomaly.model;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class ChatResponse {
    private String summary;
    private List<String> columns;
    private List<List<String>> rows;
    private String queryType;
    private boolean isTabular;
    private String errorMessage;
}
