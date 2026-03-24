package com.diplomat.gateway.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Component
public class QualityCheckClient {

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
            // If QC service is unreachable, default to pass so the user still gets a response
            System.out.println("QC service unreachable: " + e.getMessage());
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
}
