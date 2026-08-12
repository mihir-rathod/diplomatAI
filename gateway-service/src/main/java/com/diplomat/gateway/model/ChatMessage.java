package com.diplomat.gateway.model;

/**
 * Represents a single message in a conversation.
 * Used to pass full conversation context to LLM providers.
 */
public class ChatMessage {

    private String role;    // "user", "assistant", or "system"
    private String content; // The message text

    public ChatMessage() {}

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
