package com.diplomat.gateway.repository;

import com.diplomat.gateway.model.RegisteredModel;
import com.diplomat.gateway.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface RegisteredModelRepository extends JpaRepository<RegisteredModel, Long> {
    List<RegisteredModel> findByUser(User user);
    Optional<RegisteredModel> findByUserAndModelId(User user, String modelId);
    boolean existsByUserAndModelId(User user, String modelId);
    @Transactional
    void deleteByUserAndModelId(User user, String modelId);
}
