package com.bank.anomaly.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NetworkGraph {

    private List<NetworkNode> nodes;
    private List<NetworkEdge> edges;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NetworkNode {
        private String id;
        private String label;
        private String type;  // "CLIENT" or "BENEFICIARY"
        private int fanIn;    // only for BENEFICIARY nodes
        private boolean isCenter;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NetworkEdge {
        private String from;
        private String to;
    }
}
