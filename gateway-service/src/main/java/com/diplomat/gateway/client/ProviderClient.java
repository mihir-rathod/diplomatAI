package com.diplomat.gateway.client;

import com.diplomat.gateway.config.ModelRegistryProperties.ModelConfig;
import com.diplomat.gateway.config.ModelRegistryProperties;
import com.diplomat.gateway.service.RouterService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.context.annotation.Lazy;
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
    private final RouterService routerService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Use @Lazy to avoid circular dependencies if RouterService also depends on anything that relies on ProviderClient
    public ProviderClient(RestTemplate restTemplate, ModelRegistryProperties registryProperties, @Lazy RouterService routerService) {
        this.restTemplate = restTemplate;
        this.registryProperties = registryProperties;
        this.routerService = routerService;
    }

    @RateLimiter(name = "llmRateLimiter", fallbackMethod = "fallbackToBackupModel")
    @CircuitBreaker(name = "llmCircuitBreaker", fallbackMethod = "fallbackToBackupModel")
    public ModelCallResult callModel(String prompt, ModelConfig config) {
        // Internal provider is the terminal fallback — no external call needed
        if (config.getProvider().equalsIgnoreCase("internal")) {
            return ModelCallResult.success(
                    "Internal Gateway Fallback activated. No external models are available.",
                    config.getId(), config.getProvider(), 0, 0, 0, -1, -1);
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
            org.springframework.http.ResponseEntity<String> responseEntity = restTemplate.postForEntity(finalEndpoint, request, String.class);
            String jsonResponse = responseEntity.getBody();
            if (jsonResponse == null) {
                return ModelCallResult.error("System Error: Empty response from provider.", config.getId());
            }

            // Extract HTTP Headers for Rate Limits
            long rateLimitMax = -1;
            long rateLimitRemaining = -1;
            HttpHeaders respHeaders = responseEntity.getHeaders();
            
            // Standard parsing for most providers (OpenAI, Groq, OpenRouter, Together)
            if (respHeaders.getFirst("x-ratelimit-limit-tokens") != null) {
                rateLimitMax = parseHeaderSafe(respHeaders.getFirst("x-ratelimit-limit-tokens"));
            } else if (respHeaders.getFirst("ratelimit-limit") != null) { // Mistral fallback
                rateLimitMax = parseHeaderSafe(respHeaders.getFirst("ratelimit-limit"));
            } else if (respHeaders.getFirst("anthropic-ratelimit-tokens-limit") != null) { // Anthropic
                rateLimitMax = parseHeaderSafe(respHeaders.getFirst("anthropic-ratelimit-tokens-limit"));
            }

            if (respHeaders.getFirst("x-ratelimit-remaining-tokens") != null) {
                rateLimitRemaining = parseHeaderSafe(respHeaders.getFirst("x-ratelimit-remaining-tokens"));
            } else if (respHeaders.getFirst("ratelimit-remaining") != null) {
                rateLimitRemaining = parseHeaderSafe(respHeaders.getFirst("ratelimit-remaining"));
            } else if (respHeaders.getFirst("anthropic-ratelimit-tokens-remaining") != null) {
                rateLimitRemaining = parseHeaderSafe(respHeaders.getFirst("anthropic-ratelimit-tokens-remaining"));
            }

            try {
                JsonNode root = objectMapper.readTree(jsonResponse);
                String answerText;
                int promptTokens = 0, completionTokens = 0, totalTokens = 0;

                if ("gemini".equals(provider)) {
                    // Extract answer
                    answerText = root.path("candidates").get(0)
                            .path("content").path("parts").get(0)
                            .path("text").asText();
                    // Extract token usage from usageMetadata
                    JsonNode usage = root.path("usageMetadata");
                    promptTokens = usage.path("promptTokenCount").asInt(0);
                    completionTokens = usage.path("candidatesTokenCount").asInt(0);
                    totalTokens = usage.path("totalTokenCount").asInt(0);

                } else if ("anthropic".equals(provider)) {
                    // Extract answer
                    answerText = root.path("content").get(0).path("text").asText();
                    // Extract token usage
                    JsonNode usage = root.path("usage");
                    promptTokens = usage.path("input_tokens").asInt(0);
                    completionTokens = usage.path("output_tokens").asInt(0);
                    totalTokens = promptTokens + completionTokens;

                } else {
                    // OpenAI, Groq, Mistral, OpenRouter, Together — all use the same format
                    answerText = root.path("choices").get(0)
                            .path("message").path("content").asText();
                    // Extract token usage
                    JsonNode usage = root.path("usage");
                    promptTokens = usage.path("prompt_tokens").asInt(0);
                    completionTokens = usage.path("completion_tokens").asInt(0);
                    totalTokens = usage.path("total_tokens").asInt(0);
                }

                return ModelCallResult.success(answerText, config.getId(), provider,
                        promptTokens, completionTokens, totalTokens, rateLimitMax, rateLimitRemaining);

            } catch (Exception e) {
                return ModelCallResult.error(
                        "System Error parsing response: " + e.getMessage() + " | Raw: " + jsonResponse,
                        config.getId());
            }
        }

        throw new RuntimeException("No endpoint configured for model: " + config.getId());
    }

    // Resilience4j invokes this when the primary model fails or gets rate-limited
    public ModelCallResult fallbackToBackupModel(String prompt, ModelConfig originalConfig, Throwable t) {
        // Dynamically find the next best model using RouterService
        String fallbackId = routerService.getNextBestModel(java.util.Collections.singleton(originalConfig.getId()), prompt);

        if (fallbackId == null || fallbackId.equalsIgnoreCase("default") || fallbackId.equalsIgnoreCase(originalConfig.getId())) {
            return ModelCallResult.error(
                    "Error: All models and fallbacks failed. Reason: " + t.getMessage(),
                    originalConfig.getId());
        }

        ModelConfig fallbackConfig = registryProperties.getModelsAsMap().get(fallbackId);
        if (fallbackConfig == null) {
            return ModelCallResult.error(
                    "Error: Defined fallback model '" + fallbackId + "' not found in registry.",
                    originalConfig.getId());
        }

        System.out.println("RATE LIMIT OR FAILURE DETECTED ON MODEL: " + originalConfig.getId());
        System.out.println("REROUTING TO DYNAMIC FALLBACK: " + fallbackConfig.getId());

        // Call the fallback model — this returns a success result
        ModelCallResult fallbackResult = callModel(prompt, fallbackConfig);
        // Mark the result as rerouted, preserving the original model info
        fallbackResult.setWasRerouted(true);
        fallbackResult.setOriginalModelId(originalConfig.getId());
        return fallbackResult;
    }

    private long parseHeaderSafe(String val) {
        if (val == null || val.trim().isEmpty()) return -1;
        try {
            return Long.parseLong(val.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
