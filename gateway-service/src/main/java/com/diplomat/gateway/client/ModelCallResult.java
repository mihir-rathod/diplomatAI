package com.diplomat.gateway.client;

/**
 * Wraps the result of a model call, including token usage and reroute metadata.
 * This replaces the raw String return so the controller knows exactly
 * which model responded and how many tokens were consumed.
 */
public class ModelCallResult {

    private String answer;
    private String actualModelId;
    private String originalModelId;
    private boolean wasRerouted;
    private int promptTokens;
    private int completionTokens;
    private int totalTokens;
    private String provider;
    private long rateLimitMax;
    private long rateLimitRemaining;

    // ── Static factories ──

    public static ModelCallResult success(String answer, String modelId, String provider,
                                          int promptTokens, int completionTokens, int totalTokens,
                                          long rateLimitMax, long rateLimitRemaining) {
        ModelCallResult r = new ModelCallResult();
        r.answer = answer;
        r.actualModelId = modelId;
        r.originalModelId = modelId;
        r.wasRerouted = false;
        r.promptTokens = promptTokens;
        r.completionTokens = completionTokens;
        r.totalTokens = totalTokens;
        r.provider = provider;
        r.rateLimitMax = rateLimitMax;
        r.rateLimitRemaining = rateLimitRemaining;
        return r;
    }

    public static ModelCallResult rerouted(String answer, String actualModelId, String originalModelId,
                                           String provider, int promptTokens, int completionTokens, int totalTokens,
                                           long rateLimitMax, long rateLimitRemaining) {
        ModelCallResult r = new ModelCallResult();
        r.answer = answer;
        r.actualModelId = actualModelId;
        r.originalModelId = originalModelId;
        r.wasRerouted = true;
        r.promptTokens = promptTokens;
        r.completionTokens = completionTokens;
        r.totalTokens = totalTokens;
        r.provider = provider;
        r.rateLimitMax = rateLimitMax;
        r.rateLimitRemaining = rateLimitRemaining;
        return r;
    }

    public static ModelCallResult error(String errorMessage, String originalModelId) {
        ModelCallResult r = new ModelCallResult();
        r.answer = errorMessage;
        r.actualModelId = "none";
        r.originalModelId = originalModelId;
        r.wasRerouted = false;
        r.promptTokens = 0;
        r.completionTokens = 0;
        r.totalTokens = 0;
        r.provider = "none";
        r.rateLimitMax = -1;
        r.rateLimitRemaining = -1;
        return r;
    }

    // ── Getters & Setters ──

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }

    public String getActualModelId() { return actualModelId; }
    public void setActualModelId(String actualModelId) { this.actualModelId = actualModelId; }

    public String getOriginalModelId() { return originalModelId; }
    public void setOriginalModelId(String originalModelId) { this.originalModelId = originalModelId; }

    public boolean isWasRerouted() { return wasRerouted; }
    public void setWasRerouted(boolean wasRerouted) { this.wasRerouted = wasRerouted; }

    public int getPromptTokens() { return promptTokens; }
    public void setPromptTokens(int promptTokens) { this.promptTokens = promptTokens; }

    public int getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(int completionTokens) { this.completionTokens = completionTokens; }

    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public long getRateLimitMax() { return rateLimitMax; }
    public void setRateLimitMax(long rateLimitMax) { this.rateLimitMax = rateLimitMax; }

    public long getRateLimitRemaining() { return rateLimitRemaining; }
    public void setRateLimitRemaining(long rateLimitRemaining) { this.rateLimitRemaining = rateLimitRemaining; }
}
