package com.bank.anomaly.repository;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.policy.Policy;
import com.aerospike.client.policy.WritePolicy;
import com.bank.anomaly.config.AerospikeConfig;
import com.bank.anomaly.engine.isolationforest.IsolationForest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class IsolationForestModelRepository {

    private static final Logger log = LoggerFactory.getLogger(IsolationForestModelRepository.class);

    private final AerospikeClient client;
    private final String namespace;
    private final WritePolicy writePolicy;
    private final Policy readPolicy;
    private final ObjectMapper objectMapper;

    // In-memory cache of loaded models
    private final Map<String, IsolationForest> modelCache = new ConcurrentHashMap<>();

    public IsolationForestModelRepository(AerospikeClient client,
                                          @Qualifier("aerospikeNamespace") String namespace,
                                          @Qualifier("defaultWritePolicy") WritePolicy writePolicy,
                                          @Qualifier("defaultReadPolicy") Policy readPolicy) {
        this.client = client;
        this.namespace = namespace;
        this.writePolicy = writePolicy;
        this.readPolicy = readPolicy;
        this.objectMapper = new ObjectMapper();
    }

    public void save(String clientId, IsolationForest forest, int trainingSamples) {
        try {
            String modelJson = objectMapper.writeValueAsString(forest);
            Key key = new Key(namespace, AerospikeConfig.SET_IF_MODELS, clientId);

            client.put(writePolicy, key,
                    new Bin("clientId", clientId),
                    new Bin("modelJson", modelJson),
                    new Bin("featureCount", 6),
                    new Bin("treeCount", forest.getTrees().size()),
                    new Bin("trainedAt", System.currentTimeMillis()),
                    new Bin("trainSamples", trainingSamples));

            // Update cache
            modelCache.put(clientId, forest);

            log.info("Saved IF model for {}: {} trees, {} samples",
                    clientId, forest.getTrees().size(), trainingSamples);
        } catch (Exception e) {
            log.error("Failed to save IF model for {}", clientId, e);
        }
    }

    public IsolationForest load(String clientId) {
        // Check cache first
        IsolationForest cached = modelCache.get(clientId);
        if (cached != null) return cached;

        Key key = new Key(namespace, AerospikeConfig.SET_IF_MODELS, clientId);
        Record record = client.get(readPolicy, key);
        if (record == null) return null;

        try {
            String modelJson = record.getString("modelJson");
            IsolationForest forest = objectMapper.readValue(modelJson, IsolationForest.class);
            modelCache.put(clientId, forest);
            return forest;
        } catch (Exception e) {
            log.error("Failed to load IF model for {}", clientId, e);
            return null;
        }
    }

    public Map<String, Object> getModelMetadata(String clientId) {
        Key key = new Key(namespace, AerospikeConfig.SET_IF_MODELS, clientId);
        Record record = client.get(readPolicy, key);
        if (record == null) return null;

        return Map.of(
                "clientId", clientId,
                "treeCount", record.getInt("treeCount"),
                "featureCount", record.getInt("featureCount"),
                "trainingSamples", record.getInt("trainSamples"),
                "trainedAt", record.getLong("trainedAt")
        );
    }

    public void clearCache() {
        modelCache.clear();
    }
}
