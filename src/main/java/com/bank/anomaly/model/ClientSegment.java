package com.bank.anomaly.model;

public enum ClientSegment {
    HIGH_VALUE,
    GROWING,
    STABLE,
    DECLINING,
    DORMANT,
    NEW;

    public String label() {
        return switch (this) {
            case HIGH_VALUE -> "High Value";
            case GROWING -> "Growing";
            case STABLE -> "Stable";
            case DECLINING -> "Declining";
            case DORMANT -> "Dormant";
            case NEW -> "New";
        };
    }
}
