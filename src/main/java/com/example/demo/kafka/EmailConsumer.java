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

        // Notify trusted contacts for all levels
        if (level.equals("SOS Level 1") || level.equals("SOS Level 2") || level.equals("SOS Level 3")) {
            List<TrustedContact> contacts = contactRepository.findByUserId(userId);
            for (TrustedContact c : contacts) {
                sendEmail(c.getEmail(), "SOS Alert from " + alert.getUser().getName(),
                        "Level: " + level +
                                "\nLocation: " + alert.getLocation() +
                                "\nLatitude: " + alert.getLatitude() +
                                "\nLongitude: " + alert.getLongitude());
            }
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
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        mailSender.send(message);
        System.out.println("Email sent to: " + to);
    }
}
