package com.example.demo.service;

import com.example.demo.model.LiveLocationDTO;
import com.example.demo.model.SosSession;
import com.example.demo.model.TrustedContact;
import com.example.demo.model.User;
import com.example.demo.repository.SosSessionRepository;
import com.example.demo.repository.TrustedContactRepository;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LiveLocationService {

    private final SosSessionRepository sosSessionRepo;
    private final TrustedContactRepository trustedContactRepo;
    private final SimpMessagingTemplate messagingTemplate;

    public LiveLocationService(SosSessionRepository sosSessionRepo,
                               TrustedContactRepository trustedContactRepo,
                               SimpMessagingTemplate messagingTemplate) {
        this.sosSessionRepo = sosSessionRepo;
        this.trustedContactRepo = trustedContactRepo;
        this.messagingTemplate = messagingTemplate;
    }

    // Send live location if SOS is active
    public void sendLocation(User user, double latitude, double longitude) {
        // Check if SOS session is active
        SosSession activeSession = sosSessionRepo.findByUserAndActiveTrue(user)
                .orElseThrow(() -> new RuntimeException("SOS not active. Cannot update location."));

        LiveLocationDTO location = new LiveLocationDTO(latitude, longitude);

        // Push to frontend via WebSocket for the user
        messagingTemplate.convertAndSend("/topic/alerts/" + user.getId(), location);

        // Push to all trusted contacts
        List<TrustedContact> contacts = trustedContactRepo.findByUserId(user.getId());
        for (TrustedContact contact : contacts) {
            if (contact.getContactUser() != null) {
                // FIX: Send to the Contact's Account ID
                messagingTemplate.convertAndSend("/topic/alerts/" + contact.getContactUser().getId(), location);
            }
        }
    }
}
