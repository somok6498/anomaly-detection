package com.bank.anomaly.repository;

import com.bank.anomaly.engine.isolationforest.IsolationForest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class IsolationForestModelRepository {

    private static final Logger log = LoggerFactory.getLogger(IsolationForestModelRepository.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, IsolationForest> modelCache = new ConcurrentHashMap<>();

    public IsolationForestModelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(String clientId, IsolationForest forest, int trainingSamples) {
        try {
            String modelJson = objectMapper.writeValueAsString(forest);
            jdbc.update("""
                    MERGE INTO isolation_forest_models m
                    USING (SELECT ? AS client_id FROM dual) s ON (m.client_id = s.client_id)
                    WHEN MATCHED THEN UPDATE SET
                        model_json = ?, tree_count = ?, train_samples = ?, trained_at = ?
                    WHEN NOT MATCHED THEN INSERT (client_id, model_json, feature_count, tree_count, train_samples, trained_at)
                    VALUES (?, ?, 6, ?, ?, ?)
                    """,
                    clientId,
                    modelJson, forest.getTrees().size(), trainingSamples, System.currentTimeMillis(),
                    clientId, modelJson, forest.getTrees().size(), trainingSamples, System.currentTimeMillis());

            modelCache.put(clientId, forest);
            log.info("Saved IF model for {}: {} trees, {} samples",
                    clientId, forest.getTrees().size(), trainingSamples);
        } catch (Exception e) {
            log.error("Failed to save IF model for {}", clientId, e);
        }
    }

    public IsolationForest load(String clientId) {
        IsolationForest cached = modelCache.get(clientId);
        if (cached != null) return cached;

        List<String> jsonResults = jdbc.query(
                "SELECT model_json FROM isolation_forest_models WHERE client_id = ?",
                (rs, rowNum) -> rs.getString("model_json"),
                clientId);

        if (jsonResults.isEmpty()) return null;

        try {
            IsolationForest forest = objectMapper.readValue(jsonResults.get(0), IsolationForest.class);
            modelCache.put(clientId, forest);
            return forest;
        } catch (Exception e) {
            log.error("Failed to load IF model for {}", clientId, e);
            return null;
        }
    }

    public Map<String, Object> getModelMetadata(String clientId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT tree_count, feature_count, train_samples, trained_at FROM isolation_forest_models WHERE client_id = ?",
                clientId);
        if (rows.isEmpty()) return null;

        Map<String, Object> row = rows.get(0);
        return Map.of(
                "clientId", clientId,
                "treeCount", row.get("tree_count"),
                "featureCount", row.get("feature_count"),
                "trainingSamples", row.get("train_samples"),
                "trainedAt", row.get("trained_at")
        );
    }

    public void clearCache() {
        modelCache.clear();
    }
}
