package com.diplomat.gateway.service;

import com.diplomat.gateway.config.ModelRegistryProperties;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class RouterService {

    private final ModelRegistryProperties modelRegistry;

    // Keywords used for prompt categorization — extend these to improve routing
    // accuracy
    private static final List<String> CODING_KEYWORDS = List.of(
            "code", "python", "java", "script", "function", "bug", "refactor", "html", "css");

    private static final List<String> FAST_KEYWORDS = List.of(
            "hello", "hi", "summary", "brief", "quick", "joke");

    public RouterService(ModelRegistryProperties modelRegistry) {
        this.modelRegistry = modelRegistry;
    }

    public String routePrompt(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            return "default";
        }

        String lowerPrompt = prompt.toLowerCase();

        boolean isCoding = CODING_KEYWORDS.stream().anyMatch(lowerPrompt::contains);
        if (isCoding) {
            return findModelWithCapability("coding", "deepseek-coder");
        }

        boolean isFast = FAST_KEYWORDS.stream().anyMatch(lowerPrompt::contains);
        if (isFast) {
            return findModelWithCapability("fast_response", "mistral");
        }

        // No specific category matched — default to general reasoning
        return findModelWithCapability("general_chat", "llama3");
    }

    private String findModelWithCapability(String capability, String defaultId) {
        Map<String, ModelRegistryProperties.ModelConfig> models = modelRegistry.getModelsAsMap();

        for (ModelRegistryProperties.ModelConfig config : models.values()) {
            if (config.getCapabilities() != null && config.getCapabilities().contains(capability)) {
                return config.getId();
            }
        }

        return defaultId;
    }
}
