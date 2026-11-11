package com.diplomat.gateway.controller;

import java.util.Map;

public class GatewayResponse {
    private String answer;
    private Map<String, Object> metrics;

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public Map<String, Object> getMetrics() {
        return metrics;
    }

    public void setMetrics(Map<String, Object> metrics) {
        this.metrics = metrics;
    }
}
