package com.diplomat.gateway.repository;

import com.diplomat.gateway.model.Session;
import com.diplomat.gateway.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SessionRepository extends JpaRepository<Session, Long> {
    List<Session> findByUserOrderByUpdatedAtDesc(User user);
    void deleteByIdAndUser(Long id, User user);
}
