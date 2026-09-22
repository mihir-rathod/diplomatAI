package com.diplomat.gateway.config;

import com.diplomat.gateway.config.ModelRegistryProperties.ModelConfig;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

/**
 * Holds the caller's available models (their own registered models + the
 * global "default" fallback) for the lifetime of one HTTP request.
 *
 * Routing (RouterService) and provider dispatch (ProviderClient's
 * resilience4j fallback path) both need this per-caller view, but they're
 * singleton beans several calls deep — GatewayController populates this
 * once per request instead of threading the map through every method
 * signature (which would also break the resilience4j @CircuitBreaker /
 * @RateLimiter fallback-method matching on ProviderClient.callModel).
 */
@Component
@Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = org.springframework.context.annotation.ScopedProxyMode.TARGET_CLASS)
public class ActiveModelRegistry {

    private Map<String, ModelConfig> models = Map.of();

    public void setModels(Map<String, ModelConfig> models) {
        this.models = models != null ? models : Map.of();
    }

    public Map<String, ModelConfig> getModels() {
        return models;
    }
}
