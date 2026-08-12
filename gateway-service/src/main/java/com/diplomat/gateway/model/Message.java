package com.diplomat.gateway.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "messages")
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private Session session;

    @Column(nullable = false)
    private String role; // "user" or "assistant"

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    private String model;      // which model responded (null for user messages)
    private String provider;   // which provider (null for user messages)
    private boolean cachedHit; // whether this was a cache hit

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Message() {}

    public Message(Session session, String role, String content) {
        this.session = session;
        this.role = role;
        this.content = content;
    }

    public Long getId() { return id; }
    public Session getSession() { return session; }
    public void setSession(Session session) { this.session = session; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public boolean isCachedHit() { return cachedHit; }
    public void setCachedHit(boolean cachedHit) { this.cachedHit = cachedHit; }
    public Instant getCreatedAt() { return createdAt; }
}
