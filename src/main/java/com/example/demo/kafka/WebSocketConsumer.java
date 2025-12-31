package com.example.demo.kafka;

import com.example.demo.model.Alert;
import com.example.demo.model.TrustedContact;
import com.example.demo.repository.TrustedContactRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class WebSocketConsumer {

    private final SimpMessagingTemplate messagingTemplate;
    private final TrustedContactRepository trustedContactRepo;

    public WebSocketConsumer(SimpMessagingTemplate messagingTemplate, TrustedContactRepository trustedContactRepo) {
        this.messagingTemplate = messagingTemplate;
        this.trustedContactRepo = trustedContactRepo;
    }

    @KafkaListener(topics = "alerts-topic", groupId = "websocket-group")
    public void consume(Alert alert) {
        Long ownerId = alert.getUser().getId();
        messagingTemplate.convertAndSend("/topic/alerts/" + ownerId, alert);

        // Fetch contacts
        List<TrustedContact> contacts = trustedContactRepo.findByUserId(ownerId);

        System.out.println("🔍 Found " + contacts.size() + " contacts for user " + ownerId);

        for (TrustedContact contact : contacts) {
            // CHECK: Ensure the contactUser relationship exists
            if (contact.getContactUser() != null) {
                Long receiverId = contact.getContactUser().getId();

                System.out.println("📤 Sending to Contact ID: " + receiverId);

                // Send to the RECEIVER's topic
                messagingTemplate.convertAndSend("/topic/alerts/" + receiverId, alert);
            } else {
                System.err.println("⚠️ SKIPPING Contact ID " + contact.getId() + " because contactUser is NULL");
            }
        }
    }
}