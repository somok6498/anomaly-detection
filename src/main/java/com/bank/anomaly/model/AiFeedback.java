package com.bank.anomaly.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiFeedback {
    private String txnId;
    private boolean helpful;
    private String operatorId;
    private long timestamp;
}
