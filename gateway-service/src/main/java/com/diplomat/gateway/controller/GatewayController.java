package com.diplomat.gateway.controller;

import com.diplomat.gateway.model.ApiKeyRequest;
import com.diplomat.gateway.service.DynamicModelFetcherService;
import com.diplomat.gateway.service.RouterService;
import com.diplomat.gateway.client.ProviderClient;
import com.diplomat.gateway.client.QualityCheckClient;
import com.diplomat.gateway.config.ModelRegistryProperties;
import com.diplomat.gateway.config.ModelRegistryProperties.ModelConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/chat")
@CrossOrigin(origins = "*")
public class GatewayController {

    private final RouterService routerService;
    private final StringRedisTemplate redisTemplate;
    private final DynamicModelFetcherService modelFetcherService;
    private final ProviderClient providerClient;
    private final QualityCheckClient qualityCheckClient;
    private final ModelRegistryProperties modelRegistry;

    @Autowired
    public GatewayController(RouterService routerService, StringRedisTemplate redisTemplate,
            DynamicModelFetcherService modelFetcherService,
            ProviderClient providerClient,
            QualityCheckClient qualityCheckClient,
            ModelRegistryProperties modelRegistry) {
        this.routerService = routerService;
        this.redisTemplate = redisTemplate;
        this.modelFetcherService = modelFetcherService;
        this.providerClient = providerClient;
        this.qualityCheckClient = qualityCheckClient;
        this.modelRegistry = modelRegistry;
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String REGISTRY_KEY = "registry:models";

    @PostConstruct
    public void loadRegistryFromRedis() {
        try {
            String json = redisTemplate.opsForValue().get(REGISTRY_KEY);
            if (json != null && !json.isEmpty()) {
                List<ModelConfig> persisted = objectMapper.readValue(
                        json, new TypeReference<List<ModelConfig>>() {});
                if (modelRegistry.getModels() == null) {
                    modelRegistry.setModels(new ArrayList<>());
                }
                for (ModelConfig m : persisted) {
                    boolean exists = modelRegistry.getModels().stream()
                            .anyMatch(e -> e.getId().equals(m.getId()));
                    if (!exists) {
                        modelRegistry.getModels().add(m);
                    }
                }
                System.out.println("Loaded " + persisted.size() + " models from Redis.");
            }
        } catch (Exception e) {
            System.out.println("Failed to load registry from Redis: " + e.getMessage());
        }
    }

    private void persistRegistry() {
        try {
            List<ModelConfig> models = modelRegistry.getModels();
            if (models == null) models = List.of();
            // Only persist non-internal models
            List<ModelConfig> toSave = models.stream()
                    .filter(m -> !"internal".equalsIgnoreCase(m.getProvider()))
                    .collect(Collectors.toList());
            String json = objectMapper.writeValueAsString(toSave);
            redisTemplate.opsForValue().set(REGISTRY_KEY, json);
        } catch (Exception e) {
            System.out.println("Failed to persist registry to Redis: " + e.getMessage());
        }
    }

    @DeleteMapping("/cache")
    public ResponseEntity<Map<String, Object>> clearCache() {
        Set<String> keys = redisTemplate.keys("prompt:*");
        long deleted = 0;
        if (keys != null && !keys.isEmpty()) {
            deleted = keys.size();
            redisTemplate.delete(keys);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("status", "cleared");
        result.put("entries_removed", deleted);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new HashMap<>();
        status.put("status", "up");
        status.put("service", "gateway");

        try {
            redisTemplate.opsForValue().get("health-check");
            status.put("redis", "connected");
        } catch (Exception e) {
            status.put("redis", "disconnected");
        }

        int registeredModels = modelRegistry.getModels() != null ? modelRegistry.getModels().size() : 0;
        status.put("registered_models", registeredModels);

        return ResponseEntity.ok(status);
    }

    @PostMapping("/models")
    public ResponseEntity<?> fetchProviderModels(@RequestBody ApiKeyRequest request) {
        if (request.getProvider() == null || request.getApiKey() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Provider and API key are required."));
        }

        if (!modelFetcherService.isProviderSupported(request.getProvider())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Unsupported provider: " + request.getProvider(),
                    "supported", DynamicModelFetcherService.getSupportedProviders()
            ));
        }

        boolean keyValid = modelFetcherService.validateApiKey(request.getProvider(), request.getApiKey());
        if (!keyValid) {
            return ResponseEntity.status(401).body(Map.of(
                    "error", "Invalid API key for provider: " + request.getProvider()
            ));
        }

        List<ModelConfig> availableModels = modelFetcherService.fetchModels(
                request.getProvider(), request.getApiKey());

        // Add fetched models into the live registry so they become routable
        if (modelRegistry.getModels() == null) {
            modelRegistry.setModels(new ArrayList<>());
        }
        for (ModelConfig m : availableModels) {
            boolean exists = modelRegistry.getModels().stream()
                    .anyMatch(existing -> existing.getId().equals(m.getId()));
            if (!exists) {
                modelRegistry.getModels().add(m);
            }
        }

        persistRegistry();
        return ResponseEntity.ok(availableModels);
    }

    @GetMapping("/models/registry")
    public ResponseEntity<List<ModelConfig>> getRegistry() {
        List<ModelConfig> models = modelRegistry.getModels();
        return ResponseEntity.ok(models != null ? models : List.of());
    }

    @DeleteMapping("/models/registry/{modelId}")
    public ResponseEntity<Map<String, String>> removeFromRegistry(@PathVariable String modelId) {
        List<ModelConfig> models = modelRegistry.getModels();
        if (models == null) {
            return ResponseEntity.notFound().build();
        }

        boolean removed = models.removeIf(m -> m.getId().equals(modelId));
        if (removed) {
            persistRegistry();
            Map<String, String> result = new HashMap<>();
            result.put("status", "removed");
            result.put("modelId", modelId);
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping
    public ResponseEntity<GatewayResponse> processPrompt(@RequestBody GatewayRequest request) {
        String prompt = request.getPrompt();
        GatewayResponse response = new GatewayResponse();
        Map<String, Object> metrics = new HashMap<>();

        long startTime = System.currentTimeMillis();

        // Check Redis for a cached response before routing to any model
        if (request.isUseCache()) {
            String cachedAnswer = redisTemplate.opsForValue().get("prompt:" + prompt);
            if (cachedAnswer != null) {
                response.setAnswer(cachedAnswer);

                metrics.put("cache_hit", true);
                metrics.put("model_routed", "None (Served from Cache)");
                metrics.put("fallback_triggered", false);
                metrics.put("qc_score", 100);
                metrics.put("qc_passed", true);
                metrics.put("latency_ms", System.currentTimeMillis() - startTime);

                response.setMetrics(metrics);
                return ResponseEntity.ok(response);
            }

            // Exact Cache miss — Try Semantic Caching
            Set<String> keys = redisTemplate.keys("prompt:*");
            if (keys != null && !keys.isEmpty()) {
                Set<String> cachedPrompts = keys.stream()
                        .map(k -> k.substring(7)) // remove "prompt:"
                        .collect(Collectors.toSet());

                Map<String, Object> semanticMatch = qualityCheckClient.checkSemanticCache(prompt, cachedPrompts);
                if (semanticMatch.containsKey("matched") && (boolean) semanticMatch.get("matched")) {
                    String matchedPrompt = (String) semanticMatch.get("matched_prompt");
                    String semanticCachedAnswer = redisTemplate.opsForValue().get("prompt:" + matchedPrompt);

                    if (semanticCachedAnswer != null) {
                        response.setAnswer(semanticCachedAnswer);

                        metrics.put("cache_hit", true);
                        metrics.put("model_routed", "None (Semantic Cache: " + semanticMatch.get("similarity") + ")");
                        metrics.put("fallback_triggered", false);
                        metrics.put("qc_score", 100);
                        metrics.put("qc_passed", true);
                        metrics.put("latency_ms", System.currentTimeMillis() - startTime);

                        response.setMetrics(metrics);
                        return ResponseEntity.ok(response);
                    }
                }
            }
        }

        // Full Cache miss — route to the best model based on prompt categorization
        String selectedModelId = request.getModelId();
        boolean manualSelection = true;
        
        if (selectedModelId == null || selectedModelId.trim().isEmpty() || selectedModelId.equalsIgnoreCase("auto")) {
            selectedModelId = routerService.routePrompt(prompt);
            manualSelection = false;
        }
        
        metrics.put("cache_hit", false);
        metrics.put("model_routed", selectedModelId);

        ModelConfig config = modelRegistry.getModelsAsMap().get(selectedModelId);
        if (config == null) {
            config = modelRegistry.getModelsAsMap().get("default");
        }

        String generatedAnswer;
        try {
            generatedAnswer = providerClient.callModel(prompt, config);
        } catch (Exception e) {
            if (manualSelection) {
                System.err.println("Model " + selectedModelId + " explicitly failed: " + e.getMessage());
                // Attempt an automatic fallback route instead of instantly failing
                String fallbackId = routerService.routePrompt(prompt);
                ModelConfig fallbackConfig = modelRegistry.getModelsAsMap().get(fallbackId);
                if (fallbackConfig == null) {
                    fallbackConfig = modelRegistry.getModelsAsMap().get("default");
                }
                
                try {
                    generatedAnswer = providerClient.callModel(prompt, fallbackConfig);
                    metrics.put("model_routed", fallbackId + " (Auto-Recovered)");
                } catch (Exception e2) {
                    generatedAnswer = "System Error: The gateway could not reach any models or fallbacks. Reason: "
                            + e2.getMessage();
                }
            } else {
                generatedAnswer = "System Error: The gateway could not reach any models or fallbacks. Reason: "
                        + e.getMessage();
            }
        }

        // Detect if the response came from a fallback model
        boolean fallbackTriggered = generatedAnswer.contains("Fallback");

        // Only validate real AI-generated responses, skip system/fallback messages
        boolean qcPassed = true;
        int qcScore = 100;

        if (!fallbackTriggered && !generatedAnswer.startsWith("System Error")) {
            Map<String, Object> qcResult = qualityCheckClient.validate(prompt, generatedAnswer);
            qcPassed = (boolean) qcResult.getOrDefault("qc_passed", true);
            qcScore = qcResult.get("qc_score") instanceof Number 
                    ? ((Number) qcResult.get("qc_score")).intValue() : 0;

            if (!qcPassed) {
                generatedAnswer = "Response blocked by quality check: " + qcResult.getOrDefault("reason", "Unknown");
            }
        }

        // Cache the response for 1 hour to prevent duplicate API charges
        redisTemplate.opsForValue().set("prompt:" + prompt, generatedAnswer, 1, TimeUnit.HOURS);

        response.setAnswer(generatedAnswer);
        metrics.put("fallback_triggered", fallbackTriggered);
        metrics.put("qc_score", qcScore);
        metrics.put("qc_passed", qcPassed);
        metrics.put("latency_ms", System.currentTimeMillis() - startTime);

        response.setMetrics(metrics);
        return ResponseEntity.ok(response);
    }
}
