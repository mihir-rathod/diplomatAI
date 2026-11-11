package com.diplomat.gateway.model;

import com.diplomat.gateway.config.ModelRegistryProperties.ModelConfig;

import java.util.List;

/**
 * Sanitized view of ModelConfig for the public registry endpoint.
 * The raw apiKey is NEVER included — only a masked hint is returned.
 */
public class ModelConfigView {

    private String id;
    private String name;
    private String provider;
    private String apiKeyHint; // e.g. "sk-...a1b2" — first 3 + last 4 chars
    private List<String> capabilities;

    public static ModelConfigView from(ModelConfig config) {
        ModelConfigView view = new ModelConfigView();
        view.id = config.getId();
        view.name = config.getName();
        view.provider = config.getProvider();
        view.capabilities = config.getCapabilities();

        String key = config.getApiKey();
        if (key != null && key.length() > 7) {
            view.apiKeyHint = key.substring(0, 3) + "..." + key.substring(key.length() - 4);
        } else if (key != null && !key.isEmpty()) {
            view.apiKeyHint = "••••••";
        } else {
            view.apiKeyHint = null;
        }

        return view;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getProvider() { return provider; }
    public String getApiKeyHint() { return apiKeyHint; }
    public List<String> getCapabilities() { return capabilities; }
}
