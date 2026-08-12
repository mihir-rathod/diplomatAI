package com.diplomat.gateway.controller;

import com.diplomat.gateway.model.Session;
import com.diplomat.gateway.model.User;
import com.diplomat.gateway.repository.MessageRepository;
import com.diplomat.gateway.repository.SessionRepository;
import com.diplomat.gateway.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;

    public SessionController(SessionRepository sessionRepository,
                              MessageRepository messageRepository,
                              UserRepository userRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
    }

    private User getUser(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        return userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    // ── List all sessions for the current user ──
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listSessions(Authentication auth) {
        User user = getUser(auth);
        List<Session> sessions = sessionRepository.findByUserOrderByUpdatedAtDesc(user);
        List<Map<String, Object>> result = sessions.stream().map(s -> Map.<String, Object>of(
                "id", s.getId(),
                "title", s.getTitle(),
                "createdAt", s.getCreatedAt(),
                "updatedAt", s.getUpdatedAt()
        )).toList();
        return ResponseEntity.ok(result);
    }

    // ── Create a new session ──
    @PostMapping
    public ResponseEntity<Map<String, Object>> createSession(@RequestBody Map<String, String> body,
                                                              Authentication auth) {
        User user = getUser(auth);
        String title = body.getOrDefault("title", "New Chat");
        Session session = sessionRepository.save(new Session(user, title));
        log.info("Created session {} for user {}", session.getId(), user.getEmail());
        return ResponseEntity.ok(Map.of(
                "id", session.getId(),
                "title", session.getTitle(),
                "createdAt", session.getCreatedAt(),
                "updatedAt", session.getUpdatedAt()
        ));
    }

    // ── Rename a session ──
    @PatchMapping("/{id}")
    public ResponseEntity<?> renameSession(@PathVariable Long id,
                                           @RequestBody Map<String, String> body,
                                           Authentication auth) {
        User user = getUser(auth);
        Optional<Session> sessionOpt = sessionRepository.findById(id);

        if (sessionOpt.isEmpty() || !sessionOpt.get().getUser().getId().equals(user.getId())) {
            return ResponseEntity.notFound().build();
        }

        Session session = sessionOpt.get();
        String newTitle = body.get("title");
        if (newTitle != null && !newTitle.isBlank()) {
            session.setTitle(newTitle.trim());
            sessionRepository.save(session);
        }

        return ResponseEntity.ok(Map.of("id", session.getId(), "title", session.getTitle()));
    }

    // ── Delete a session and all its messages ──
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> deleteSession(@PathVariable Long id, Authentication auth) {
        User user = getUser(auth);
        Optional<Session> sessionOpt = sessionRepository.findById(id);

        if (sessionOpt.isEmpty() || !sessionOpt.get().getUser().getId().equals(user.getId())) {
            return ResponseEntity.notFound().build();
        }

        Session session = sessionOpt.get();
        messageRepository.deleteBySession(session);
        sessionRepository.delete(session);
        log.info("Deleted session {} for user {}", id, user.getEmail());

        return ResponseEntity.ok(Map.of("status", "deleted", "id", id));
    }
}
