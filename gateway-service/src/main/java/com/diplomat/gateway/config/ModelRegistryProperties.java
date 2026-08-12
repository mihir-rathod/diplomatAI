package com.diplomat.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Configuration
@ConfigurationProperties(prefix = "gateway")
public class ModelRegistryProperties {

    private List<ModelConfig> models;

    public List<ModelConfig> getModels() {
        return models;
    }

    public void setModels(List<ModelConfig> models) {
        this.models = models;
    }

    // To map models by their ID for fast lookup during routing
    public Map<String, ModelConfig> getModelsAsMap() {
        if (models == null)
            return Map.of();
        return models.stream()
                .filter(m -> m != null && m.getId() != null)
                .collect(Collectors.toMap(m -> m.getId(), m -> m));
    }

    public static class ModelConfig {
        private String id;
        private String name;
        private String provider;
        private String endpoint;
        private String apiKey;
        private int timeoutMs;
        private List<String> capabilities;
        private String fallbackId;

        // Getters and Setters
        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public List<String> getCapabilities() {
            return capabilities;
        }

        public void setCapabilities(List<String> capabilities) {
            this.capabilities = capabilities;
        }

        public String getFallbackId() {
            return fallbackId;
        }

        public void setFallbackId(String fallbackId) {
            this.fallbackId = fallbackId;
        }
    }
}
