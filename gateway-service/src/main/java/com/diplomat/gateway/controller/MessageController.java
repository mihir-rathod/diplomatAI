package com.diplomat.gateway.controller;

import com.diplomat.gateway.model.Message;
import com.diplomat.gateway.model.Session;
import com.diplomat.gateway.model.User;
import com.diplomat.gateway.repository.MessageRepository;
import com.diplomat.gateway.repository.SessionRepository;
import com.diplomat.gateway.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/messages")
public class MessageController {

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;

    public MessageController(SessionRepository sessionRepository,
                              MessageRepository messageRepository,
                              UserRepository userRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
    }

    @GetMapping
    public ResponseEntity<?> getMessages(@PathVariable Long sessionId, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Optional<Session> sessionOpt = sessionRepository.findById(sessionId);
        if (sessionOpt.isEmpty() || !sessionOpt.get().getUser().getId().equals(user.getId())) {
            return ResponseEntity.notFound().build();
        }

        List<Message> messages = messageRepository.findBySessionOrderByCreatedAtAsc(sessionOpt.get());
        List<Map<String, Object>> result = messages.stream().map(m -> {
            Map<String, Object> msg = new java.util.LinkedHashMap<>();
            msg.put("id", m.getId());
            msg.put("role", m.getRole());
            msg.put("content", m.getContent());
            msg.put("model", m.getModel());
            msg.put("provider", m.getProvider());
            msg.put("cachedHit", m.isCachedHit());
            msg.put("createdAt", m.getCreatedAt());
            return msg;
        }).toList();

        return ResponseEntity.ok(result);
    }
}
