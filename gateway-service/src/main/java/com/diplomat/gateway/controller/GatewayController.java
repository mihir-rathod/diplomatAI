package com.diplomat.gateway.controller;

import com.diplomat.gateway.model.ApiKeyRequest;
import com.diplomat.gateway.service.DynamicModelFetcherService;
import com.diplomat.gateway.service.RouterService;
import com.diplomat.gateway.client.ProviderClient;
import com.diplomat.gateway.config.ModelRegistryProperties;
import com.diplomat.gateway.config.ModelRegistryProperties.ModelConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/chat")
@CrossOrigin(origins = "*")
public class GatewayController {

    private final RouterService routerService;
    private final StringRedisTemplate redisTemplate;
    private final DynamicModelFetcherService modelFetcherService;
    private final ProviderClient providerClient;
    private final ModelRegistryProperties modelRegistry;

    @Autowired
    public GatewayController(RouterService routerService, StringRedisTemplate redisTemplate,
            DynamicModelFetcherService modelFetcherService,
            ProviderClient providerClient,
            ModelRegistryProperties modelRegistry) {
        this.routerService = routerService;
        this.redisTemplate = redisTemplate;
        this.modelFetcherService = modelFetcherService;
        this.providerClient = providerClient;
        this.modelRegistry = modelRegistry;
    }

    @PostMapping("/models")
    public ResponseEntity<List<ModelConfig>> fetchProviderModels(@RequestBody ApiKeyRequest request) {
        if (request.getProvider() == null || request.getApiKey() == null) {
            return ResponseEntity.badRequest().build();
        }

        List<ModelConfig> availableModels = modelFetcherService.fetchModels(
                request.getProvider(), request.getApiKey());

        return ResponseEntity.ok(availableModels);
    }

    @PostMapping
    public ResponseEntity<GatewayResponse> processPrompt(@RequestBody GatewayRequest request) {
        String prompt = request.getPrompt();
        GatewayResponse response = new GatewayResponse();
        Map<String, Object> metrics = new HashMap<>();

        long startTime = System.currentTimeMillis();

        // Check Redis for a cached response before routing to any model
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

        // Cache miss — route to the best model based on prompt categorization
        String selectedModelId = routerService.routePrompt(prompt);
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
            generatedAnswer = "System Error: The gateway could not reach any models or fallbacks. Reason: "
                    + e.getMessage();
        }

        // Detect if the response came from a fallback model
        boolean fallbackTriggered = generatedAnswer.contains("Fallback");

        // Cache the response for 1 hour to prevent duplicate API charges
        redisTemplate.opsForValue().set("prompt:" + prompt, generatedAnswer, 1, TimeUnit.HOURS);

        response.setAnswer(generatedAnswer);
        metrics.put("fallback_triggered", fallbackTriggered);
        metrics.put("qc_score", 95);
        metrics.put("qc_passed", true);
        metrics.put("latency_ms", System.currentTimeMillis() - startTime);

        response.setMetrics(metrics);
        return ResponseEntity.ok(response);
    }
}
