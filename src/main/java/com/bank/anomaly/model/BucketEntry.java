package com.bank.anomaly.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BucketEntry {
    private long timestamp;
    private long count;
    private double sum;
    private double max;
    private double min;
    private String scope;
    private String metric;
}
