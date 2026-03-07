package com.bank.anomaly.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Client-beneficiary network graph for visualization")
public class NetworkGraph {

    @Schema(description = "Graph nodes (clients and beneficiaries)")
    private List<NetworkNode> nodes;

    @Schema(description = "Edges connecting clients to beneficiaries")
    private List<NetworkEdge> edges;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "A node in the network graph")
    public static class NetworkNode {
        @Schema(example = "CLIENT-007", description = "Node identifier")
        private String id;

        @Schema(example = "CLIENT-007", description = "Display label")
        private String label;

        @Schema(example = "CLIENT", description = "Node type: CLIENT or BENEFICIARY")
        private String type;

        @Schema(example = "3", description = "Number of clients sending to this beneficiary (BENEFICIARY nodes only)")
        private int fanIn;

        @Schema(example = "true", description = "Whether this is the center/queried node")
        private boolean isCenter;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "An edge in the network graph")
    public static class NetworkEdge {
        @Schema(example = "CLIENT-007", description = "Source node ID")
        private String from;

        @Schema(example = "BENF-ACC-001", description = "Target node ID")
        private String to;
    }
}
