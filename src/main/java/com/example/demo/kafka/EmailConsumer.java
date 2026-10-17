package com.example.demo.kafka;

import com.example.demo.model.Alert;
import com.example.demo.model.TrustedContact;
import com.example.demo.repository.TrustedContactRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class EmailConsumer {

    private final TrustedContactRepository contactRepository;
    private final JavaMailSender mailSender;

    public EmailConsumer(TrustedContactRepository contactRepository, JavaMailSender mailSender) {
        this.contactRepository = contactRepository;
        this.mailSender = mailSender;
    }

    @KafkaListener(topics = "alerts-topic", groupId = "email-group", containerFactory = "kafkaListenerContainerFactory")
    public void consume(Alert alert) {
        String level = alert.getLevel();
        Long userId = alert.getUser().getId();

        System.out.println("[DEBUG-SOS] Kafka Consumer received alert: " + level + " for user ID: " + userId);

        // Normalize level for more flexible matching
        String normalizedLevel = (level != null) ? level.toUpperCase() : "";
        boolean isSos = normalizedLevel.contains("SOS") || normalizedLevel.contains("LEVEL");

        if (isSos) {
            List<TrustedContact> contacts = contactRepository.findByUserId(userId);
            System.out.println("[DEBUG-SOS] Consumer found " + contacts.size() + " trusted contacts");

            for (TrustedContact c : contacts) {
                // Try to get email from both possible sources: contactUser or direct email field
                String toEmail = (c.getContactUser() != null) ? c.getContactUser().getEmail() : c.getEmail();
                
                if (toEmail != null && !toEmail.isEmpty()) {
                    System.out.println("[DEBUG-SOS] Consumer attempting email to: " + toEmail);
                    sendEmail(toEmail, "SOS Alert from " + alert.getUser().getName(),
                            "Level: " + level +
                                    "\nLocation: " + alert.getLocation() +
                                    "\nLatitude: " + alert.getLatitude() +
                                    "\nLongitude: " + alert.getLongitude());
                } else {
                    System.out.println("[DEBUG-SOS] Consumer skipping contact " + (c.getName() != null ? c.getName() : "Unknown") + " because email is empty");
                }
            }
        } else {
            System.out.println("[DEBUG-SOS] Alert level '" + level + "' did not match SOS criteria in consumer");
        }

        // Notify police for Level 2 and 3
        if (level.equals("SOS Level 2") || level.equals("SOS Level 3")) {
            sendEmail("police@example.com", "Emergency Alert",
                    "User: " + alert.getUser().getName() + "\nLevel: " + level +
                            "\nLocation: " + alert.getLocation() +
                            "\nLatitude: " + alert.getLatitude() +
                            "\nLongitude: " + alert.getLongitude());
        }

        // Auto-call for Level 3
        if (level.equals("SOS Level 3")) {
            System.out.println("Calling emergency number 112 for user " + alert.getUser().getName());
        }
    }

    // Helper method to send emails
    private void sendEmail(String to, String subject, String text) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject(subject);
            message.setText(text);
            mailSender.send(message);
            System.out.println("[DEBUG-SOS] Consumer successfully sent email to: " + to);
        } catch (Exception e) {
            System.err.println("[ERROR-MAIL] Consumer failed to send email to " + to + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
}
