package com.bank.anomaly.controller;

import com.bank.anomaly.service.BeneficiaryGraphService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/graph")
@Tag(name = "Beneficiary Graph", description = "In-memory beneficiary graph used for mule network detection")
public class GraphController {

    private final BeneficiaryGraphService graphService;

    public GraphController(BeneficiaryGraphService graphService) {
        this.graphService = graphService;
    }

    @GetMapping("/status")
    @Operation(summary = "Get graph status",
               description = "Returns graph metadata: total beneficiaries, clients, last refresh time")
    public ResponseEntity<Map<String, Object>> getGraphStatus() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("isReady", graphService.isGraphReady());
        response.put("totalBeneficiaries", graphService.getTotalBeneficiaryKeys());
        response.put("totalClients", graphService.getTotalClientCount());
        response.put("lastRefreshTime", graphService.getLastRefreshTime().toString());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/beneficiary/{ifsc}/{account}")
    @Operation(summary = "Get beneficiary fan-in details",
               description = "Returns the number of senders and their client IDs for a specific beneficiary")
    public ResponseEntity<Map<String, Object>> getBeneficiaryFanIn(
            @PathVariable String ifsc, @PathVariable String account) {
        String beneKey = ifsc + ":" + account;
        Set<String> senders = graphService.getOtherSenders(beneKey, "");
        int fanIn = graphService.getFanInCount(beneKey);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("beneficiaryKey", beneKey);
        response.put("fanInCount", fanIn);
        response.put("senders", senders);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/client/{clientId}")
    @Operation(summary = "Get client graph metrics",
               description = "Returns shared beneficiary count, ratio, and network density for a client")
    public ResponseEntity<Map<String, Object>> getClientGraphMetrics(@PathVariable String clientId) {
        int totalBene = graphService.getTotalBeneficiaryCount(clientId);
        int sharedBene = graphService.getSharedBeneficiaryCount(clientId);
        double density = graphService.getNetworkDensity(clientId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("clientId", clientId);
        response.put("totalBeneficiaries", totalBene);
        response.put("sharedBeneficiaries", sharedBene);
        response.put("sharedBeneficiaryPct", totalBene > 0 ? Math.round(sharedBene * 1000.0 / totalBene) / 10.0 : 0.0);
        response.put("networkDensity", Math.round(density * 1000.0) / 1000.0);
        return ResponseEntity.ok(response);
    }
}
