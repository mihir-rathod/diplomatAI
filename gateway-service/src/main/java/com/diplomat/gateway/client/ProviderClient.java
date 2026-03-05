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

    /**
     * Sends the prompt to the specified model.
     * Wrapped in Resilience4j RateLimiter & CircuitBreaker.
     * If 429 occurs (or it crashes), it actively reroutes to the fallback method.
     */
    @RateLimiter(name = "llmRateLimiter", fallbackMethod = "fallbackToBackupModel")
    @CircuitBreaker(name = "llmCircuitBreaker", fallbackMethod = "fallbackToBackupModel")
    public String callModel(String prompt, ModelConfig config) {

        // This is a simplified mock call structure.
        // In a full implementation, you'd branch by config.getProvider() (e.g. format
        // for OpenAI vs Ollama)

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (config.getApiKey() != null && !config.getApiKey().isEmpty()) {
            headers.setBearerAuth(config.getApiKey());
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", config.getId());
        body.put("prompt", prompt);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        // Simulated HTTP Call (since we don't have local Ollama running yet)
        if (config.getProvider().equalsIgnoreCase("internal")) {
            return "Internal Gateway Fallback activated. Safe response served.";
        }

        // We throw an exception here to simulate a Rate Limit / 429 hit for testing the
        // fallback!
        throw new RuntimeException("429 Too Many Requests - Simulated Quota Exceeded for " + config.getId());

        // return restTemplate.postForObject(config.getEndpoint(), request,
        // String.class);
    }

    /**
     * The highly-available Fallback Architecture.
     * If the primary model fails or gets Rate-Limited, this method is automatically
     * invoked.
     */
    public String fallbackToBackupModel(String prompt, ModelConfig originalConfig, Throwable t) {
        String fallbackId = originalConfig.getFallbackId();

        if (fallbackId == null || fallbackId.equalsIgnoreCase("none")) {
            return "Error: All models and fallbacks failed. Reason: " + t.getMessage();
        }

        ModelConfig fallbackConfig = registryProperties.getModelsAsMap().get(fallbackId);
        if (fallbackConfig == null) {
            return "Error: Defined fallback model '" + fallbackId + "' not found in registry.";
        }

        // Recursively call the backup model.
        // It's safe because if the fallback fails, it triggers ITS OWN fallback!
        System.out.println("RATE LIMIT OR FAILURE DETECTED: " + originalConfig.getId());
        System.out.println("REROUTING TO FALLBACK: " + fallbackConfig.getId());

        return callModel(prompt, fallbackConfig);
    }
}
