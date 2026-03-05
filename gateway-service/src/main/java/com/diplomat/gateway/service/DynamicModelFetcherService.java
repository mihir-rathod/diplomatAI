package com.diplomat.gateway.service;

import com.diplomat.gateway.config.ModelRegistryProperties.ModelConfig;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class DynamicModelFetcherService {

    // TODO: Replace mock responses with actual calls to provider model-listing APIs
    public List<ModelConfig> fetchModels(String provider, String apiKey) {
        List<ModelConfig> dynamicModels = new ArrayList<>();

        if ("openai".equalsIgnoreCase(provider)) {
            dynamicModels
                    .add(createConfig("gpt-4o", "OpenAI GPT-4o", provider, apiKey, List.of("coding", "reasoning")));
            dynamicModels.add(createConfig("gpt-3.5-turbo", "OpenAI GPT-3.5", provider, apiKey,
                    List.of("fast_response", "general_chat")));
        } else if ("gemini".equalsIgnoreCase(provider)) {
            dynamicModels.add(
                    createConfig("gemini-1.5-pro", "Gemini 1.5 Pro", provider, apiKey, List.of("reasoning", "coding")));
            dynamicModels.add(
                    createConfig("gemini-1.5-flash", "Gemini 1.5 Flash", provider, apiKey, List.of("fast_response")));
        }

        return dynamicModels;
    }

    private ModelConfig createConfig(String id, String name, String provider, String apiKey,
            List<String> capabilities) {
        ModelConfig config = new ModelConfig();
        config.setId(id);
        config.setName(name);
        config.setProvider(provider);
        config.setApiKey(apiKey);
        config.setCapabilities(capabilities);
        config.setTimeoutMs(15000);
        return config;
    }
}
