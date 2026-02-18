package com.bank.anomaly.controller;

import com.bank.anomaly.repository.IsolationForestModelRepository;
import com.bank.anomaly.service.IsolationForestTrainingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/models")
@Tag(name = "Models", description = "Isolation Forest model training and metadata")
public class ModelController {

    private final IsolationForestTrainingService trainingService;
    private final IsolationForestModelRepository modelRepository;

    public ModelController(IsolationForestTrainingService trainingService,
                           IsolationForestModelRepository modelRepository) {
        this.trainingService = trainingService;
        this.modelRepository = modelRepository;
    }

    @Operation(summary = "Train IF model for a specific client",
            description = "Trains an Isolation Forest model using the client's historical transactions. " +
                    "The model learns normal behavioral patterns across 6 feature dimensions and is persisted for real-time evaluation.")
    @PostMapping("/train/{clientId}")
    public ResponseEntity<Map<String, Object>> trainForClient(
            @Parameter(description = "Client ID", example = "CLIENT-001")
            @PathVariable String clientId,
            @Parameter(description = "Number of isolation trees", example = "100")
            @RequestParam(defaultValue = "100") int numTrees,
            @Parameter(description = "Sub-sampling size per tree", example = "256")
            @RequestParam(defaultValue = "256") int sampleSize) {

        trainingService.trainForClient(clientId, numTrees, sampleSize);

        Map<String, Object> metadata = modelRepository.getModelMetadata(clientId);
        if (metadata == null) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Training failed for " + clientId));
        }
        return ResponseEntity.ok(metadata);
    }

    @Operation(summary = "Train IF models for all clients",
            description = "Batch operation: trains Isolation Forest models for all known clients with sufficient transaction history.")
    @PostMapping("/train")
    public ResponseEntity<Map<String, String>> trainAll(
            @Parameter(description = "Number of isolation trees", example = "100")
            @RequestParam(defaultValue = "100") int numTrees,
            @Parameter(description = "Sub-sampling size per tree", example = "256")
            @RequestParam(defaultValue = "256") int sampleSize) {

        trainingService.trainAll(numTrees, sampleSize);
        return ResponseEntity.ok(Map.of("status", "Training complete for all clients"));
    }

    @Operation(summary = "Get model metadata",
            description = "Returns metadata about the trained IF model: tree count, feature count, training samples, and training timestamp.")
    @GetMapping("/{clientId}")
    public ResponseEntity<?> getModelMetadata(
            @Parameter(description = "Client ID", example = "CLIENT-001")
            @PathVariable String clientId) {
        Map<String, Object> metadata = modelRepository.getModelMetadata(clientId);
        if (metadata == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(metadata);
    }
}
