package com.diplomat.gateway.service;

import com.diplomat.gateway.config.ModelRegistryProperties;
import com.diplomat.gateway.config.ModelRegistryProperties.ModelConfig;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class RouterService {

    private final ModelRegistryProperties modelRegistry;

    // Keywords used for prompt categorization
    private static final List<String> CODING_KEYWORDS = List.of(
            "code", "python", "java", "script", "function", "bug", "refactor", "html", "css",
            "algorithm", "debug", "compile", "class", "method", "api", "sql", "database",
            "sort", "array", "loop", "variable", "import", "typescript", "react", "node");

    private static final List<String> FAST_KEYWORDS = List.of(
            "hello", "hi", "summary", "brief", "quick", "joke", "thanks", "yes", "no",
            "what is", "define", "explain briefly");

    // Provider priority lists — first available model from these providers wins
    private static final List<String> CODING_PROVIDER_PRIORITY = List.of(
            "groq", "mistral", "gemini", "openai", "anthropic", "openrouter", "together");

    private static final List<String> FAST_PROVIDER_PRIORITY = List.of(
            "groq", "mistral", "gemini", "openai", "openrouter", "together", "anthropic");

    private static final List<String> GENERAL_PROVIDER_PRIORITY = List.of(
            "gemini", "mistral", "groq", "openai", "anthropic", "openrouter", "together");

    public RouterService(ModelRegistryProperties modelRegistry) {
        this.modelRegistry = modelRegistry;
    }

    public String routePrompt(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            return "default";
        }

        String lowerPrompt = prompt.toLowerCase();

        if (CODING_KEYWORDS.stream().anyMatch(lowerPrompt::contains)) {
            return findBestAvailableModel(CODING_PROVIDER_PRIORITY, null);
        }

        if (FAST_KEYWORDS.stream().anyMatch(lowerPrompt::contains)) {
            return findBestAvailableModel(FAST_PROVIDER_PRIORITY, null);
        }

        return findBestAvailableModel(GENERAL_PROVIDER_PRIORITY, null);
    }

    /**
     * Determines the next best model to route to, excluding the ones that already failed.
     * Keeps the original context (coding/fast/general) based on the prompt.
     */
    public String getNextBestModel(java.util.Set<String> failedModelIds, String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            return "default";
        }

        String lowerPrompt = prompt.toLowerCase();

        if (CODING_KEYWORDS.stream().anyMatch(lowerPrompt::contains)) {
            return findBestAvailableModel(CODING_PROVIDER_PRIORITY, failedModelIds);
        }

        if (FAST_KEYWORDS.stream().anyMatch(lowerPrompt::contains)) {
            return findBestAvailableModel(FAST_PROVIDER_PRIORITY, failedModelIds);
        }

        return findBestAvailableModel(GENERAL_PROVIDER_PRIORITY, failedModelIds);
    }

    /**
     * Walks the provider priority list and returns the first model ID
     * that actually exists in the live registry, ignoring excluded IDs.
     */
    private String findBestAvailableModel(List<String> providerPriority, java.util.Set<String> excludeModelIds) {
        Map<String, ModelConfig> models = modelRegistry.getModelsAsMap();

        for (String preferredProvider : providerPriority) {
            for (ModelConfig config : models.values()) {
                if (config.getProvider() != null &&
                    config.getProvider().equalsIgnoreCase(preferredProvider) &&
                    !"internal".equalsIgnoreCase(config.getProvider())) {
                    
                    if (excludeModelIds != null && excludeModelIds.contains(config.getId())) {
                        continue; // Skip models we know have failed
                    }
                    return config.getId();
                }
            }
        }

        return "default";
    }
}
