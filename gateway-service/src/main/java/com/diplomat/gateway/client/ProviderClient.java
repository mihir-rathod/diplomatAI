package com.diplomat.gateway.client;

import com.diplomat.gateway.config.ModelRegistryProperties.ModelConfig;
import com.diplomat.gateway.config.ModelRegistryProperties;
import com.diplomat.gateway.model.ChatMessage;
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
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(ProviderClient.class);

    private final RestTemplate restTemplate;
    private final ModelRegistryProperties registryProperties;
    private final RouterService routerService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProviderClient(RestTemplate restTemplate, ModelRegistryProperties registryProperties, @Lazy RouterService routerService) {
        this.restTemplate = restTemplate;
        this.registryProperties = registryProperties;
        this.routerService = routerService;
    }

    @RateLimiter(name = "llmRateLimiter", fallbackMethod = "fallbackToBackupModel")
    @CircuitBreaker(name = "llmCircuitBreaker", fallbackMethod = "fallbackToBackupModel")
    public ModelCallResult callModel(List<ChatMessage> messages, ModelConfig config) {
        // Internal provider is the terminal fallback
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
            // Gemini uses: contents: [{role: "user"/"model", parts: [{text: "..."}]}]
            List<Map<String, Object>> contents = new ArrayList<>();
            for (ChatMessage msg : messages) {
                Map<String, Object> content = new HashMap<>();
                // Gemini uses "model" instead of "assistant"
                String geminiRole = "assistant".equals(msg.getRole()) ? "model" : msg.getRole();
                content.put("role", geminiRole);
                Map<String, Object> part = new HashMap<>();
                part.put("text", msg.getContent());
                content.put("parts", Collections.singletonList(part));
                contents.add(content);
            }
            body.put("contents", contents);
        } else if ("anthropic".equals(provider)) {
            // Anthropic uses: messages: [{role, content}], system prompt goes top-level
            body.put("model", config.getId());
            body.put("max_tokens", 8192);
            List<Map<String, String>> anthropicMessages = new ArrayList<>();
            for (ChatMessage msg : messages) {
                // Anthropic doesn't support "system" role in messages — skip or handle differently
                if ("system".equals(msg.getRole())) continue;
                Map<String, String> m = new HashMap<>();
                m.put("role", msg.getRole());
                m.put("content", msg.getContent());
                anthropicMessages.add(m);
            }
            body.put("messages", anthropicMessages);
        } else {
            // OpenAI, Groq, Mistral, OpenRouter, Together — all use messages: [{role, content}]
            body.put("model", config.getId());
            List<Map<String, String>> openaiMessages = new ArrayList<>();
            for (ChatMessage msg : messages) {
                Map<String, String> m = new HashMap<>();
                m.put("role", msg.getRole());
                m.put("content", msg.getContent());
                openaiMessages.add(m);
            }
            body.put("messages", openaiMessages);
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
            
            if (respHeaders.getFirst("x-ratelimit-limit-tokens") != null) {
                rateLimitMax = parseHeaderSafe(respHeaders.getFirst("x-ratelimit-limit-tokens"));
            } else if (respHeaders.getFirst("ratelimit-limit") != null) {
                rateLimitMax = parseHeaderSafe(respHeaders.getFirst("ratelimit-limit"));
            } else if (respHeaders.getFirst("anthropic-ratelimit-tokens-limit") != null) {
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
                    answerText = root.path("candidates").get(0)
                            .path("content").path("parts").get(0)
                            .path("text").asText();
                    JsonNode usage = root.path("usageMetadata");
                    promptTokens = usage.path("promptTokenCount").asInt(0);
                    completionTokens = usage.path("candidatesTokenCount").asInt(0);
                    totalTokens = usage.path("totalTokenCount").asInt(0);

                } else if ("anthropic".equals(provider)) {
                    answerText = root.path("content").get(0).path("text").asText();
                    JsonNode usage = root.path("usage");
                    promptTokens = usage.path("input_tokens").asInt(0);
                    completionTokens = usage.path("output_tokens").asInt(0);
                    totalTokens = promptTokens + completionTokens;

                } else {
                    answerText = root.path("choices").get(0)
                            .path("message").path("content").asText();
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

    // Resilience4j fallback — now accepts List<ChatMessage> to match updated signature
    public ModelCallResult fallbackToBackupModel(List<ChatMessage> messages, ModelConfig originalConfig, Throwable t) {
        String lastPrompt = "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).getRole())) {
                lastPrompt = messages.get(i).getContent();
                break;
            }
        }

        String fallbackId = routerService.getNextBestModel(java.util.Collections.singleton(originalConfig.getId()), lastPrompt);

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

        log.warn("Rate limit or failure on model '{}': {}", originalConfig.getId(), t.getMessage());
        log.info("Rerouting to fallback model: '{}'", fallbackConfig.getId());

        // Call the fallback model with full conversation context
        ModelCallResult fallbackResult = callModel(messages, fallbackConfig);
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

