package com.example.demo.service;

import com.example.demo.model.SosSession;
import com.example.demo.model.User;
import com.example.demo.repository.SosSessionRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class SosSessionService {

    private final SosSessionRepository repo;

    public SosSessionService(SosSessionRepository repo) {
        this.repo = repo;
    }

    public SosSession startSession(User user) {
        Optional<SosSession> active = repo.findByUserAndActiveTrue(user);
        if (active.isPresent()) return active.get();

        SosSession session = new SosSession();
        session.setUser(user);
        session.setActive(true);
        session.setStartedAt(LocalDateTime.now());

        return repo.save(session);
    }

    public void stopSession(User user) {
        SosSession session = repo.findByUserAndActiveTrue(user)
                .orElseThrow(() -> new RuntimeException("No active SOS"));
        session.setActive(false);
        session.setEndedAt(LocalDateTime.now());
        repo.save(session);
    }
}
