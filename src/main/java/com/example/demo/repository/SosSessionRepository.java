package com.example.demo.repository;

import com.example.demo.model.SosSession;
import com.example.demo.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SosSessionRepository extends JpaRepository<SosSession, Long> {
    Optional<SosSession> findByUserAndActiveTrue(User user);
}
