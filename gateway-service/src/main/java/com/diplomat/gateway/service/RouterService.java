package com.diplomat.gateway.service;

import com.diplomat.gateway.config.ModelRegistryProperties;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class RouterService {

    private final ModelRegistryProperties modelRegistry;

    // A lightweight heuristic keyword map to determine categories
    private static final List<String> CODING_KEYWORDS = List.of(
            "code", "python", "java", "script", "function", "bug", "refactor", "html", "css");

    private static final List<String> FAST_KEYWORDS = List.of(
            "hello", "hi", "summary", "brief", "quick", "joke");

    public RouterService(ModelRegistryProperties modelRegistry) {
        this.modelRegistry = modelRegistry;
    }

    /**
     * Parses the prompt and returns the ID of the best model to route it to.
     */
    public String routePrompt(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            return "default";
        }

        String lowerPrompt = prompt.toLowerCase();

        // 1. Check for coding tasks
        boolean isCoding = CODING_KEYWORDS.stream().anyMatch(lowerPrompt::contains);
        if (isCoding) {
            return findModelWithCapability("coding", "deepseek-coder");
        }

        // 2. Check for fast/simple queries
        boolean isFast = FAST_KEYWORDS.stream().anyMatch(lowerPrompt::contains);
        if (isFast) {
            return findModelWithCapability("fast_response", "mistral");
        }

        // 3. Default to general reasoning
        return findModelWithCapability("general_chat", "llama3");
    }

    /**
     * Helper to find a model by capability, falling back to a hardcoded default if
     * not found
     * Ensure the config actually has a model that matches.
     */
    private String findModelWithCapability(String capability, String defaultId) {
        Map<String, ModelRegistryProperties.ModelConfig> models = modelRegistry.getModelsAsMap();

        for (ModelRegistryProperties.ModelConfig config : models.values()) {
            if (config.getCapabilities() != null && config.getCapabilities().contains(capability)) {
                return config.getId();
            }
        }

        // If no model has the capability, return the default requested
        return defaultId;
    }
}
