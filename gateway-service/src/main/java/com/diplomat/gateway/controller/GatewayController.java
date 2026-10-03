package com.diplomat.gateway.controller;

import com.diplomat.gateway.model.ApiKeyRequest;
import com.diplomat.gateway.model.ChatMessage;
import com.diplomat.gateway.model.Message;
import com.diplomat.gateway.model.ModelConfigView;
import com.diplomat.gateway.model.RegisteredModel;
import com.diplomat.gateway.model.User;
import com.diplomat.gateway.repository.MessageRepository;
import com.diplomat.gateway.repository.RegisteredModelRepository;
import com.diplomat.gateway.repository.SessionRepository;
import com.diplomat.gateway.repository.UserRepository;
import com.diplomat.gateway.security.ApiKeyCipher;
import com.diplomat.gateway.service.DynamicModelFetcherService;
import com.diplomat.gateway.service.RouterService;
import com.diplomat.gateway.client.ProviderClient;
import com.diplomat.gateway.client.QualityCheckClient;
import com.diplomat.gateway.client.ModelCallResult;
import com.diplomat.gateway.config.ActiveModelRegistry;
import com.diplomat.gateway.config.ModelRegistryProperties;
import com.diplomat.gateway.config.ModelRegistryProperties.ModelConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/chat")
public class GatewayController {

    private static final Logger log = LoggerFactory.getLogger(GatewayController.class);

    private final RouterService routerService;
    private final StringRedisTemplate redisTemplate;
    private final DynamicModelFetcherService modelFetcherService;
    private final ProviderClient providerClient;
    private final QualityCheckClient qualityCheckClient;
    private final ModelRegistryProperties modelRegistry;
    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final RegisteredModelRepository registeredModelRepository;
    private final ActiveModelRegistry activeModelRegistry;
    private final ApiKeyCipher apiKeyCipher;

    @Autowired
    public GatewayController(RouterService routerService, StringRedisTemplate redisTemplate,
            DynamicModelFetcherService modelFetcherService,
            ProviderClient providerClient,
            QualityCheckClient qualityCheckClient,
            ModelRegistryProperties modelRegistry,
            SessionRepository sessionRepository,
            MessageRepository messageRepository,
            UserRepository userRepository,
            RegisteredModelRepository registeredModelRepository,
            ActiveModelRegistry activeModelRegistry,
            ApiKeyCipher apiKeyCipher) {
        this.routerService = routerService;
        this.redisTemplate = redisTemplate;
        this.modelFetcherService = modelFetcherService;
        this.providerClient = providerClient;
        this.qualityCheckClient = qualityCheckClient;
        this.modelRegistry = modelRegistry;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.registeredModelRepository = registeredModelRepository;
        this.activeModelRegistry = activeModelRegistry;
        this.apiKeyCipher = apiKeyCipher;
    }

    private User currentUser(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        // A valid JWT for a user that no longer exists (e.g. after a DB reset) must read as "sign in again", not a 500
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User no longer exists"));
    }

    private ModelConfig toModelConfig(RegisteredModel rm) {
        ModelConfig config = new ModelConfig();
        config.setId(rm.getModelId());
        config.setName(rm.getName());
        config.setProvider(rm.getProvider());
        config.setEndpoint(rm.getEndpoint());
        config.setApiKey(apiKeyCipher.decrypt(rm.getApiKeyEncrypted()));
        config.setTimeoutMs(rm.getTimeoutMs());
        config.setCapabilities(rm.getCapabilities());
        return config;
    }

    /** The caller's own registered models plus the global "default" fallback from models.yaml. */
    private Map<String, ModelConfig> buildAvailableModels(User user) {
        Map<String, ModelConfig> available = new HashMap<>();
        ModelConfig defaultConfig = modelRegistry.getModelsAsMap().get("default");
        if (defaultConfig != null) {
            available.put("default", defaultConfig);
        }
        for (RegisteredModel rm : registeredModelRepository.findByUser(user)) {
            available.put(rm.getModelId(), toModelConfig(rm));
        }
        return available;
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
    public ResponseEntity<?> fetchProviderModels(@RequestBody ApiKeyRequest request, Authentication auth) {
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

        User user = currentUser(auth);
        List<ModelConfig> fetchedModels = modelFetcherService.fetchModels(
                request.getProvider(), request.getApiKey());
        String encryptedKey = apiKeyCipher.encrypt(request.getApiKey());

        // Register (or refresh) these models against the caller's own account
        for (ModelConfig m : fetchedModels) {
            RegisteredModel rm = registeredModelRepository.findByUserAndModelId(user, m.getId())
                    .orElseGet(RegisteredModel::new);
            rm.setUser(user);
            rm.setModelId(m.getId());
            rm.setName(m.getName());
            rm.setProvider(m.getProvider());
            rm.setEndpoint(m.getEndpoint());
            rm.setApiKeyEncrypted(encryptedKey);
            rm.setTimeoutMs(m.getTimeoutMs());
            rm.setCapabilities(m.getCapabilities());
            registeredModelRepository.save(rm);
        }

        return ResponseEntity.ok(fetchedModels);
    }

    @GetMapping("/models/registry")
    public ResponseEntity<List<ModelConfigView>> getRegistry(Authentication auth) {
        User user = currentUser(auth);
        List<ModelConfigView> views = registeredModelRepository.findByUser(user).stream()
                .map(rm -> ModelConfigView.from(toModelConfig(rm)))
                .collect(Collectors.toList());
        return ResponseEntity.ok(views);
    }

    // {*modelId} (not {modelId}) so IDs containing "/" like "meta-llama/llama-3.1-8b-instruct:free" match
    @DeleteMapping("/models/registry/{*modelId}")
    public ResponseEntity<Map<String, String>> removeFromRegistry(@PathVariable String modelId, Authentication auth) {
        if (modelId.startsWith("/")) modelId = modelId.substring(1);
        User user = currentUser(auth);
        if (!registeredModelRepository.existsByUserAndModelId(user, modelId)) {
            return ResponseEntity.notFound().build();
        }

        registeredModelRepository.deleteByUserAndModelId(user, modelId);
        Map<String, String> result = new HashMap<>();
        result.put("status", "removed");
        result.put("modelId", modelId);
        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<GatewayResponse> processPrompt(@RequestBody GatewayRequest request,
                                                          Authentication auth) {
        User user = currentUser(auth);
        Map<String, ModelConfig> availableModels = buildAvailableModels(user);
        activeModelRegistry.setModels(availableModels);

        String prompt = request.getPrompt();
        GatewayResponse response = new GatewayResponse();
        Map<String, Object> metrics = new HashMap<>();

        long startTime = System.currentTimeMillis();

        // ── 1. Check Redis for a cached response before routing ──
        if (request.isUseCache()) {
            String cachedAnswer = redisTemplate.opsForValue().get("prompt:" + prompt);
            if (cachedAnswer != null) {
                response.setAnswer(cachedAnswer);

                metrics.put("cache_hit", true);
                metrics.put("model_routed", "cache");
                metrics.put("fallback_triggered", false);
                metrics.put("prompt_tokens", 0);
                metrics.put("completion_tokens", 0);
                metrics.put("total_tokens", 0);
                metrics.put("provider", "cache");
                metrics.put("latency_ms", System.currentTimeMillis() - startTime);

                response.setMetrics(metrics);
                return ResponseEntity.ok(response);
            }

            // Exact cache miss — try Semantic Caching via QC service
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
                        metrics.put("model_routed", "semantic-cache");
                        metrics.put("fallback_triggered", false);
                        metrics.put("semantic_similarity", semanticMatch.get("similarity"));
                        metrics.put("prompt_tokens", 0);
                        metrics.put("completion_tokens", 0);
                        metrics.put("total_tokens", 0);
                        metrics.put("provider", "cache");
                        metrics.put("latency_ms", System.currentTimeMillis() - startTime);

                        response.setMetrics(metrics);
                        return ResponseEntity.ok(response);
                    }
                }
            }
        }

        // ── 2. Full cache miss — build messages and route to the best model ──
        // Build conversation context: prefer messages[] from request, fallback to wrapping prompt
        List<ChatMessage> conversationMessages;
        if (request.getMessages() != null && !request.getMessages().isEmpty()) {
            conversationMessages = request.getMessages();
        } else {
            conversationMessages = new ArrayList<>();
            conversationMessages.add(new ChatMessage("user", prompt));
        }

        String selectedModelId = request.getModelId();
        
        if (selectedModelId == null || selectedModelId.trim().isEmpty() || selectedModelId.equalsIgnoreCase("auto")) {
            selectedModelId = routerService.routePrompt(prompt);
        }

        ModelConfig config = availableModels.get(selectedModelId);
        if (config == null) {
            config = availableModels.get("default");
        }

        // ── 3. Call the model with seamless fallback loop ──
        ModelCallResult result = ModelCallResult.error("Initialization", selectedModelId);
        java.util.Set<String> failedModels = new java.util.HashSet<>();
        String currentModelId = selectedModelId;
        boolean isFirstAttempt = true;

        while (true) {
            ModelConfig currentConfig = availableModels.get(currentModelId);
            if (currentConfig == null) {
                currentConfig = availableModels.get("default");
            }

            try {
                result = providerClient.callModel(conversationMessages, currentConfig);
                
                // If it returned an error (e.g. from internal fallback circuit breaker), treat as failure
                if (result.getAnswer() != null && result.getAnswer().startsWith("Error:")) {
                    throw new RuntimeException(result.getAnswer());
                }

                // If this wasn't the first attempt, mark it explicitly as rerouted
                if (!isFirstAttempt) {
                    result.setWasRerouted(true);
                    result.setOriginalModelId(selectedModelId);
                }
                
                // Success! Break out of loop.
                break;
                
            } catch (Exception e) {
                log.warn("Model '{}' failed: {}", currentModelId, e.getMessage());
                failedModels.add(currentModelId);
                
                // Get next best model avoiding all failed ones so far
                String nextModelId = routerService.getNextBestModel(failedModels, prompt);
                
                // If we ran out of fallbacks, return final error
                if ("default".equals(nextModelId) || failedModels.contains(nextModelId)) {
                    result = ModelCallResult.error(
                        "System Error: The gateway could not reach any models. Reason: " + e.getMessage(),
                        selectedModelId);
                    break;
                }
                
                currentModelId = nextModelId;
                isFirstAttempt = false;
            }
        }

        // ── 4. Cache the response ──
        boolean isError = result.getAnswer().startsWith("System Error") || result.getAnswer().startsWith("Error:");
        if (!isError) {
            redisTemplate.opsForValue().set("prompt:" + prompt, result.getAnswer(), 1, TimeUnit.HOURS);
        }

        // ── 5. Persist messages to Postgres (if sessionId provided and not an error) ──
        final ModelCallResult finalResult = result; // capture for lambda (result is not effectively final)
        if (!isError && request.getSessionId() != null) {
            try {
                sessionRepository.findById(request.getSessionId()).ifPresent(session -> {
                    if (session.getUser().getId().equals(user.getId())) {
                        // Persist the user's message
                        Message userMessage = new Message(session, "user", prompt);
                        messageRepository.save(userMessage);

                        // Persist the assistant's reply
                        Message assistantMessage = new Message(session, "assistant", finalResult.getAnswer());
                        assistantMessage.setModel(finalResult.getActualModelId());
                        assistantMessage.setProvider(finalResult.getProvider());
                        messageRepository.save(assistantMessage);

                        // Touch updatedAt on the session
                        session.setUpdatedAt(java.time.Instant.now());
                        sessionRepository.save(session);
                    }
                });
            } catch (Exception e) {
                log.warn("Failed to persist messages to session {}: {}", request.getSessionId(), e.getMessage());
            }
        }

        // ── 6. Build response metrics ──
        response.setAnswer(result.getAnswer());

        metrics.put("cache_hit", false);
        metrics.put("model_routed", result.getActualModelId());
        metrics.put("fallback_triggered", result.isWasRerouted());
        if (result.isWasRerouted()) {
            metrics.put("original_model", result.getOriginalModelId());
        }
        metrics.put("prompt_tokens", result.getPromptTokens());
        metrics.put("completion_tokens", result.getCompletionTokens());
        metrics.put("total_tokens", result.getTotalTokens());
        metrics.put("provider", result.getProvider());
        metrics.put("latency_ms", System.currentTimeMillis() - startTime);
        metrics.put("rate_limit_max", result.getRateLimitMax());
        metrics.put("rate_limit_remaining", result.getRateLimitRemaining());

        response.setMetrics(metrics);
        return ResponseEntity.ok(response);
    }
}
