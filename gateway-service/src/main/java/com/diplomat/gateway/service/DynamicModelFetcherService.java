package com.diplomat.gateway.service;

import com.diplomat.gateway.config.ModelRegistryProperties.ModelConfig;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DynamicModelFetcherService {

    private final RestTemplate restTemplate;

    // Supported providers and their model-listing endpoints
    private static final Map<String, String> PROVIDER_ENDPOINTS = Map.of(
            "openai", "https://api.openai.com/v1/models",
            "gemini", "https://generativelanguage.googleapis.com/v1beta/models",
            "anthropic", "https://api.anthropic.com/v1/models",
            "groq", "https://api.groq.com/openai/v1/models",
            "mistral", "https://api.mistral.ai/v1/models",
            "openrouter", "https://openrouter.ai/api/v1/models",
            "together", "https://api.together.xyz/v1/models"
    );

    public static Set<String> getSupportedProviders() {
        return PROVIDER_ENDPOINTS.keySet();
    }

    public DynamicModelFetcherService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public boolean isProviderSupported(String provider) {
        return PROVIDER_ENDPOINTS.containsKey(provider.toLowerCase());
    }

    public boolean validateApiKey(String provider, String apiKey) {
        String endpoint = PROVIDER_ENDPOINTS.get(provider.toLowerCase());
        if (endpoint == null) return false;

        try {
            HttpHeaders headers = new HttpHeaders();

            if ("gemini".equalsIgnoreCase(provider)) {
                // Gemini uses query param for auth
                endpoint += "?key=" + apiKey;
            } else if ("anthropic".equalsIgnoreCase(provider)) {
                headers.set("x-api-key", apiKey);
                headers.set("anthropic-version", "2023-06-01");
            } else {
                headers.setBearerAuth(apiKey);
            }

            HttpEntity<Void> request = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    endpoint, org.springframework.http.HttpMethod.GET, request, String.class);

            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    public List<ModelConfig> fetchModels(String provider, String apiKey) {
        List<ModelConfig> dynamicModels = new ArrayList<>();

        // TODO: Parse actual API responses to build model lists dynamically
        if ("openai".equalsIgnoreCase(provider)) {
            dynamicModels.add(createConfig("gpt-4o", "OpenAI GPT-4o", provider, apiKey,
                    "https://api.openai.com/v1/chat/completions", List.of("coding", "reasoning")));
            dynamicModels.add(createConfig("gpt-3.5-turbo", "OpenAI GPT-3.5 Turbo", provider, apiKey,
                    "https://api.openai.com/v1/chat/completions", List.of("fast_response", "general_chat")));
        } else if ("gemini".equalsIgnoreCase(provider)) {
            dynamicModels.add(createConfig("gemini-1.5-pro-latest", "Gemini 1.5 Pro", provider, apiKey,
                    "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-pro-latest:generateContent",
                    List.of("reasoning", "coding")));
            dynamicModels.add(createConfig("gemini-1.5-flash-latest", "Gemini 1.5 Flash", provider, apiKey,
                    "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent",
                    List.of("fast_response")));
        } else if ("anthropic".equalsIgnoreCase(provider)) {
            dynamicModels.add(createConfig("claude-3-5-sonnet", "Claude 3.5 Sonnet", provider, apiKey,
                    "https://api.anthropic.com/v1/messages", List.of("coding", "reasoning")));
            dynamicModels.add(createConfig("claude-3-haiku", "Claude 3 Haiku", provider, apiKey,
                    "https://api.anthropic.com/v1/messages", List.of("fast_response", "general_chat")));
        } else if ("groq".equalsIgnoreCase(provider)) {
            dynamicModels.add(createConfig("llama-3.3-70b-versatile", "Llama 3.3 70B (Groq)", provider, apiKey,
                    "https://api.groq.com/openai/v1/chat/completions", List.of("general_chat", "reasoning")));
            dynamicModels.add(createConfig("llama-3.1-8b-instant", "Llama 3.1 8B Instant (Groq)", provider, apiKey,
                    "https://api.groq.com/openai/v1/chat/completions", List.of("fast_response", "coding")));
        } else if ("mistral".equalsIgnoreCase(provider)) {
            dynamicModels.add(createConfig("mistral-large-latest", "Mistral Large", provider, apiKey,
                    "https://api.mistral.ai/v1/chat/completions", List.of("coding", "reasoning")));
            dynamicModels.add(createConfig("mistral-small-latest", "Mistral Small", provider, apiKey,
                    "https://api.mistral.ai/v1/chat/completions", List.of("fast_response", "general_chat")));
        } else if ("openrouter".equalsIgnoreCase(provider)) {
            dynamicModels.add(createConfig("meta-llama/llama-3.1-8b-instruct:free", "Llama 3.1 8B (Free)", provider, apiKey,
                    "https://openrouter.ai/api/v1/chat/completions", List.of("general_chat", "fast_response")));
            dynamicModels.add(createConfig("mistralai/mistral-7b-instruct:free", "Mistral 7B (Free)", provider, apiKey,
                    "https://openrouter.ai/api/v1/chat/completions", List.of("coding", "fast_response")));
        } else if ("together".equalsIgnoreCase(provider)) {
            dynamicModels.add(createConfig("meta-llama/Meta-Llama-3.1-8B-Instruct-Turbo", "Llama 3.1 8B Turbo", provider, apiKey,
                    "https://api.together.xyz/v1/chat/completions", List.of("general_chat", "fast_response")));
            dynamicModels.add(createConfig("Qwen/Qwen2.5-Coder-32B-Instruct", "Qwen 2.5 Coder 32B", provider, apiKey,
                    "https://api.together.xyz/v1/chat/completions", List.of("coding", "reasoning")));
        }

        return dynamicModels;
    }

    private ModelConfig createConfig(String id, String name, String provider, String apiKey,
            String endpoint, List<String> capabilities) {
        ModelConfig config = new ModelConfig();
        config.setId(id);
        config.setName(name);
        config.setProvider(provider);
        config.setApiKey(apiKey);
        config.setEndpoint(endpoint);
        config.setCapabilities(capabilities);
        config.setTimeoutMs(15000);
        return config;
    }
}
