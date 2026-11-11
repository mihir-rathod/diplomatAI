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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

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

    // Clears only the caller's own cache entries; other accounts' cached answers are untouched
    @DeleteMapping("/cache")
    public ResponseEntity<Map<String, Object>> clearCache(Authentication auth) {
        User user = currentUser(auth);
        Set<String> keys = redisTemplate.keys("prompt:" + user.getId() + ":*");
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

    private static final int MAX_SEMANTIC_CANDIDATES = 200;
    private static final Pattern CACHE_SAFE_MODEL = Pattern.compile("[\\w./:@-]+");

    /**
     * Cache entries are scoped to the user and the model the user asked for ("auto" or a model id),
     * so one account can never be served another account's answer, and a pinned model is never
     * answered with a different model's cached reply. Returns null when the model id has characters
     * that are unsafe in a Redis key pattern, which disables the cache for that request.
     */
    private String cacheKeyPrefix(User user, String requestedModelId) {
        String model = (requestedModelId == null || requestedModelId.isBlank() || requestedModelId.equalsIgnoreCase("auto"))
                ? "auto" : requestedModelId.trim();
        if (!CACHE_SAFE_MODEL.matcher(model).matches()) return null;
        return "prompt:" + user.getId() + ":" + model + ":";
    }

    private ResponseEntity<GatewayResponse> cacheHitResponse(String answer, String modelLabel, Object similarity, long startTime) {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("cache_hit", true);
        metrics.put("model_routed", modelLabel);
        metrics.put("fallback_triggered", false);
        if (similarity != null) {
            metrics.put("semantic_similarity", similarity);
        }
        metrics.put("prompt_tokens", 0);
        metrics.put("completion_tokens", 0);
        metrics.put("total_tokens", 0);
        metrics.put("provider", "cache");
        metrics.put("latency_ms", System.currentTimeMillis() - startTime);

        GatewayResponse response = new GatewayResponse();
        response.setAnswer(answer);
        response.setMetrics(metrics);
        return ResponseEntity.ok(response);
    }

    private void persistTurn(User user, Long sessionId, String prompt, String answer,
                             String model, String provider, boolean cachedHit) {
        if (sessionId == null) return;
        try {
            sessionRepository.findById(sessionId).ifPresent(session -> {
                if (!session.getUser().getId().equals(user.getId())) return;

                messageRepository.save(new Message(session, "user", prompt));

                Message assistantMessage = new Message(session, "assistant", answer);
                assistantMessage.setModel(model);
                assistantMessage.setProvider(provider);
                assistantMessage.setCachedHit(cachedHit);
                messageRepository.save(assistantMessage);

                session.setUpdatedAt(Instant.now());
                sessionRepository.save(session);
            });
        } catch (Exception e) {
            log.warn("Failed to persist messages to session {}: {}", sessionId, e.getMessage());
        }
    }

    @PostMapping
    public ResponseEntity<GatewayResponse> processPrompt(@RequestBody GatewayRequest request,
                                                          Authentication auth) {
        User user = currentUser(auth);
        Map<String, ModelConfig> availableModels = buildAvailableModels(user);
        activeModelRegistry.setModels(availableModels);
        activeModelRegistry.setUserId(user.getId());

        String prompt = request.getPrompt();
        long startTime = System.currentTimeMillis();

        // Conversation context: prefer messages[] from the request, fall back to wrapping the prompt
        List<ChatMessage> conversationMessages;
        if (request.getMessages() != null && !request.getMessages().isEmpty()) {
            conversationMessages = request.getMessages();
        } else {
            conversationMessages = new ArrayList<>();
            conversationMessages.add(new ChatMessage("user", prompt));
        }

        // ── 1. Cache lookup ──
        // Only a standalone first-turn prompt is cacheable. A follow-up ("explain more") only has
        // meaning given the earlier turns, so it is neither read from nor written to the cache.
        String cachePrefix = cacheKeyPrefix(user, request.getModelId());
        boolean cacheable = cachePrefix != null && conversationMessages.size() <= 1;

        if (cacheable && request.isUseCache()) {
            String cachedAnswer = redisTemplate.opsForValue().get(cachePrefix + prompt);
            if (cachedAnswer != null) {
                persistTurn(user, request.getSessionId(), prompt, cachedAnswer, "cache", "cache", true);
                return cacheHitResponse(cachedAnswer, "cache", null, startTime);
            }

            // Exact miss — try semantic matching against this user's own cached prompts
            Set<String> keys = redisTemplate.keys(cachePrefix + "*");
            if (keys != null && !keys.isEmpty()) {
                Set<String> cachedPrompts = keys.stream()
                        .map(k -> k.substring(cachePrefix.length()))
                        .limit(MAX_SEMANTIC_CANDIDATES)
                        .collect(Collectors.toSet());

                Map<String, Object> semanticMatch = qualityCheckClient.checkSemanticCache(prompt, cachedPrompts);
                if (semanticMatch.containsKey("matched") && (boolean) semanticMatch.get("matched")) {
                    String matchedPrompt = (String) semanticMatch.get("matched_prompt");
                    String semanticCachedAnswer = redisTemplate.opsForValue().get(cachePrefix + matchedPrompt);

                    if (semanticCachedAnswer != null) {
                        persistTurn(user, request.getSessionId(), prompt, semanticCachedAnswer, "semantic-cache", "cache", true);
                        return cacheHitResponse(semanticCachedAnswer, "semantic-cache",
                                semanticMatch.get("similarity"), startTime);
                    }
                }
            }
        }

        // ── 2. Cache miss — route to the best model ──
        String selectedModelId = request.getModelId();

        if (selectedModelId == null || selectedModelId.trim().isEmpty() || selectedModelId.equalsIgnoreCase("auto")) {
            selectedModelId = routerService.routePrompt(prompt);
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

        // ── 4. Cache the response (never errors, never the gateway's own "no models" fallback text) ──
        boolean isError = result.getAnswer().startsWith("System Error") || result.getAnswer().startsWith("Error:");
        boolean fromRealModel = !"internal".equalsIgnoreCase(result.getProvider());
        if (cacheable && !isError && fromRealModel) {
            redisTemplate.opsForValue().set(cachePrefix + prompt, result.getAnswer(), 1, TimeUnit.HOURS);
        }

        // ── 5. Persist the turn to Postgres (if a sessionId was provided and the call did not fail) ──
        if (!isError) {
            persistTurn(user, request.getSessionId(), prompt, result.getAnswer(),
                    result.getActualModelId(), result.getProvider(), false);
        }

        // ── 6. Build response metrics ──
        GatewayResponse response = new GatewayResponse();
        Map<String, Object> metrics = new HashMap<>();
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
