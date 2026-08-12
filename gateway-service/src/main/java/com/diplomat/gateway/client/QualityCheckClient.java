package com.diplomat.gateway.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

@Component
public class QualityCheckClient {

    private static final Logger log = LoggerFactory.getLogger(QualityCheckClient.class);

    private final RestTemplate restTemplate;

    @Value("${qc.service.url}")
    private String qcServiceUrl;

    public QualityCheckClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> validate(String prompt, String answer) {
        Map<String, String> payload = new HashMap<>();
        payload.put("prompt", prompt);
        payload.put("answer", answer);

        try {
            Map<String, Object> result = restTemplate.postForObject(
                    qcServiceUrl + "/api/v1/validate", payload, Map.class);
            return result != null ? result : defaultPassResponse();
        } catch (Exception e) {
            log.warn("QC service unreachable, defaulting to pass: {}", e.getMessage());
            return defaultPassResponse();
        }
    }

    private Map<String, Object> defaultPassResponse() {
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("qc_passed", true);
        fallback.put("qc_score", 0);
        fallback.put("reason", "QC service unavailable, defaulting to pass.");
        return fallback;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> checkSemanticCache(String prompt, java.util.Set<String> cachedPrompts) {
        if (cachedPrompts == null || cachedPrompts.isEmpty()) {
            return java.util.Collections.emptyMap();
        }
        
        Map<String, Object> payload = new HashMap<>();
        payload.put("prompt", prompt);
        payload.put("cached_prompts", cachedPrompts);

        try {
            Map<String, Object> result = restTemplate.postForObject(
                    qcServiceUrl + "/api/v1/cache", payload, Map.class);
            return result != null ? result : java.util.Collections.emptyMap();
        } catch (Exception e) {
            log.warn("Semantic cache check failed: {}", e.getMessage());
            return java.util.Collections.emptyMap();
        }
    }
}
