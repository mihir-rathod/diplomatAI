package com.diplomat.gateway.client;

import com.diplomat.gateway.config.ModelRegistryProperties.ModelConfig;
import com.diplomat.gateway.config.ModelRegistryProperties;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.HashMap;

@Component
public class ProviderClient {

    private final RestTemplate restTemplate;
    private final ModelRegistryProperties registryProperties;

    public ProviderClient(RestTemplate restTemplate, ModelRegistryProperties registryProperties) {
        this.restTemplate = restTemplate;
        this.registryProperties = registryProperties;
    }

    @RateLimiter(name = "llmRateLimiter", fallbackMethod = "fallbackToBackupModel")
    @CircuitBreaker(name = "llmCircuitBreaker", fallbackMethod = "fallbackToBackupModel")
    public String callModel(String prompt, ModelConfig config) {

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (config.getApiKey() != null && !config.getApiKey().isEmpty()) {
            headers.setBearerAuth(config.getApiKey());
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", config.getId());
        body.put("prompt", prompt);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        // Internal provider is the terminal fallback — no external call needed
        if (config.getProvider().equalsIgnoreCase("internal")) {
            return "Internal Gateway Fallback activated. Safe response served.";
        }

        if (config.getEndpoint() != null && !config.getEndpoint().isEmpty()) {
            return restTemplate.postForObject(config.getEndpoint(), request, String.class);
        }

        throw new RuntimeException("No endpoint configured for model: " + config.getId());
    }

    // Resilience4j invokes this when the primary model fails or gets rate-limited
    public String fallbackToBackupModel(String prompt, ModelConfig originalConfig, Throwable t) {
        String fallbackId = originalConfig.getFallbackId();

        if (fallbackId == null || fallbackId.equalsIgnoreCase("none")) {
            return "Error: All models and fallbacks failed. Reason: " + t.getMessage();
        }

        ModelConfig fallbackConfig = registryProperties.getModelsAsMap().get(fallbackId);
        if (fallbackConfig == null) {
            return "Error: Defined fallback model '" + fallbackId + "' not found in registry.";
        }

        System.out.println("RATE LIMIT OR FAILURE DETECTED: " + originalConfig.getId());
        System.out.println("REROUTING TO FALLBACK: " + fallbackConfig.getId());

        return callModel(prompt, fallbackConfig);
    }
}
