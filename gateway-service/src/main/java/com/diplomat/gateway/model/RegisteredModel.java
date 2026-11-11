package com.diplomat.gateway.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "registered_models", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user_id", "model_id"})
})
public class RegisteredModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "model_id", nullable = false)
    private String modelId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String endpoint;

    // AES-GCM encrypted, base64-encoded — see ApiKeyCipher
    @Column(name = "api_key_encrypted", nullable = false, columnDefinition = "TEXT")
    private String apiKeyEncrypted;

    private int timeoutMs = 15000;

    @ElementCollection
    @CollectionTable(name = "registered_model_capabilities", joinColumns = @JoinColumn(name = "registered_model_id"))
    @Column(name = "capability")
    private List<String> capabilities;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public RegisteredModel() {}

    public Long getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getApiKeyEncrypted() { return apiKeyEncrypted; }
    public void setApiKeyEncrypted(String apiKeyEncrypted) { this.apiKeyEncrypted = apiKeyEncrypted; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    public List<String> getCapabilities() { return capabilities; }
    public void setCapabilities(List<String> capabilities) { this.capabilities = capabilities; }
    public Instant getCreatedAt() { return createdAt; }
}
