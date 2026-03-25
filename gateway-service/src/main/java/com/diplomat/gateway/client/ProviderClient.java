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
import java.util.Collections;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class ProviderClient {

    private final RestTemplate restTemplate;
    private final ModelRegistryProperties registryProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProviderClient(RestTemplate restTemplate, ModelRegistryProperties registryProperties) {
        this.restTemplate = restTemplate;
        this.registryProperties = registryProperties;
    }

    @RateLimiter(name = "llmRateLimiter", fallbackMethod = "fallbackToBackupModel")
    @CircuitBreaker(name = "llmCircuitBreaker", fallbackMethod = "fallbackToBackupModel")
    public String callModel(String prompt, ModelConfig config) {
        // Internal provider is the terminal fallback — no external call needed
        if (config.getProvider().equalsIgnoreCase("internal")) {
            return "Internal Gateway Fallback activated. Safe response served.";
        }

        String provider = config.getProvider().toLowerCase();
        String finalEndpoint = config.getEndpoint();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        if ("gemini".equals(provider)) {
            finalEndpoint += "?key=" + config.getApiKey();
        } else if ("anthropic".equals(provider)) {
            headers.set("x-api-key", config.getApiKey());
            headers.set("anthropic-version", "2023-06-01");
        } else {
            if (config.getApiKey() != null && !config.getApiKey().isEmpty()) {
                headers.setBearerAuth(config.getApiKey());
            }
        }

        Map<String, Object> body = new HashMap<>();

        if ("gemini".equals(provider)) {
            Map<String, Object> part = new HashMap<>();
            part.put("text", prompt);
            Map<String, Object> content = new HashMap<>();
            content.put("parts", Collections.singletonList(part));
            body.put("contents", Collections.singletonList(content));
        } else if ("anthropic".equals(provider)) {
            body.put("model", config.getId());
            body.put("max_tokens", 8192);
            Map<String, String> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", prompt);
            body.put("messages", Collections.singletonList(message));
        } else {
            // OpenAI, Groq, Mistral, OpenRouter, Together
            body.put("model", config.getId());
            Map<String, String> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", prompt);
            body.put("messages", Collections.singletonList(message));
        }

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        if (finalEndpoint != null && !finalEndpoint.isEmpty()) {
            String jsonResponse = restTemplate.postForObject(finalEndpoint, request, String.class);
            if (jsonResponse == null) return "System Error: Empty response from provider.";

            try {
                JsonNode root = objectMapper.readTree(jsonResponse);
                if ("gemini".equals(provider)) {
                    return root.path("candidates").get(0)
                            .path("content").path("parts").get(0)
                            .path("text").asText();
                } else if ("anthropic".equals(provider)) {
                    return root.path("content").get(0).path("text").asText();
                } else {
                    return root.path("choices").get(0)
                            .path("message").path("content").asText();
                }
            } catch (Exception e) {
                return "System Error parsing response: " + e.getMessage() + " | Raw: " + jsonResponse;
            }
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
