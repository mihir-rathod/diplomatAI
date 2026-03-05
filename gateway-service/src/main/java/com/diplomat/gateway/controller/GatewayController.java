package com.diplomat.gateway.controller;

import com.diplomat.gateway.model.ApiKeyRequest;
import com.diplomat.gateway.service.DynamicModelFetcherService;
import com.diplomat.gateway.service.RouterService;
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
@CrossOrigin(origins = "*") // Allow Streamlit dashboard to hit this
public class GatewayController {

    private final RouterService routerService;
    private final StringRedisTemplate redisTemplate;
    private final DynamicModelFetcherService modelFetcherService;

    @Autowired
    public GatewayController(RouterService routerService, StringRedisTemplate redisTemplate,
            DynamicModelFetcherService modelFetcherService) {
        this.routerService = routerService;
        this.redisTemplate = redisTemplate;
        this.modelFetcherService = modelFetcherService;
    }

    @PostMapping("/models")
    public ResponseEntity<List<ModelConfig>> fetchProviderModels(@RequestBody ApiKeyRequest request) {
        if (request.getProvider() == null || request.getApiKey() == null) {
            return ResponseEntity.badRequest().build();
        }

        // Dynamically fetch models from the provider using the provided key
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

        // 1. Check Memory (Redis)
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

        // 2. Intelligent Routing if Cache Miss
        String selectedModelId = routerService.routePrompt(prompt);
        metrics.put("cache_hit", false);
        metrics.put("model_routed", selectedModelId);

        // TODO: (Next Phase) Implement restTemplate/webClient call to the actual LLM
        // via Resilience4j
        // TODO: (Next Phase) Implement restTemplate/webClient call to Quality Check
        // Python Service

        // --- Temporary Mock Answer for initial integration ---
        String generatedAnswer = "This is a temporary mocked response from the Java Gateway. You asked: " + prompt
                + ". I routed this to: " + selectedModelId;

        // 3. Save to Memory for 1 hour
        redisTemplate.opsForValue().set("prompt:" + prompt, generatedAnswer, 1, TimeUnit.HOURS);

        response.setAnswer(generatedAnswer);
        metrics.put("fallback_triggered", false);
        metrics.put("qc_score", 95); // mocked
        metrics.put("qc_passed", true); // mocked
        metrics.put("latency_ms", System.currentTimeMillis() - startTime);

        response.setMetrics(metrics);
        return ResponseEntity.ok(response);
    }
}
