package com.diplomat.gateway.repository;

import com.diplomat.gateway.model.Message;
import com.diplomat.gateway.model.Session;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findBySessionOrderByCreatedAtAsc(Session session);
    void deleteBySession(Session session);
}
