package com.example.demo.service;

import com.example.demo.model.Alert;
import com.example.demo.model.TrustedContact;
import com.example.demo.model.User;
import com.example.demo.repository.AlertRepository;
import com.example.demo.repository.TrustedContactRepository;
import com.example.demo.repository.UserRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
//import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AlertService {

    private final AlertRepository alertRepository;
    private final KafkaTemplate<String, Alert> kafkaTemplate;
    private final UserRepository userRepository;
    private final TrustedContactRepository contactRepository;
//    private final SimpMessagingTemplate messagingTemplate;
    private final JavaMailSender mailSender;

    public AlertService(AlertRepository alertRepository,
                        KafkaTemplate<String, Alert> kafkaTemplate,
                        UserRepository userRepository,
                        TrustedContactRepository contactRepository,
//                        SimpMessagingTemplate messagingTemplate,
                        JavaMailSender mailSender) {
        this.alertRepository = alertRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.userRepository = userRepository;
        this.contactRepository = contactRepository;
//        this.messagingTemplate = messagingTemplate;
        this.mailSender = mailSender;
    }

    public Alert sendAlert(Long userId, String level, String location, double latitude, double longitude) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Alert alert = new Alert();
        alert.setUser(user);
        alert.setLevel(level);
        alert.setLocation(location);
        alert.setLatitude(latitude);
        alert.setLongitude(longitude);
        alert.setTimestamp(LocalDateTime.now());

        // Generate live tracking link
        String liveTrackingUrl = "http://localhost:5173/live-location/" + user.getId();
        alert.setTrackingUrl(liveTrackingUrl); // make sure Alert entity has this field

        // Save alert in DB
        alertRepository.save(alert);
        System.out.println("[DEBUG-SOS] Alert saved to DB for user: " + user.getName() + " with level: " + level);

        // Send alert to Kafka topic
        kafkaTemplate.send("alerts-topic", alert);

        // Notify trusted contacts via WebSocket AND Email
        List<TrustedContact> contacts = contactRepository.findByUserId(userId);
        System.out.println("[DEBUG-SOS] Found " + contacts.size() + " trusted contacts for user ID: " + userId);

        for (TrustedContact contact : contacts) {
            // Ensure contactUser exists before emailing
            if (contact.getContactUser() != null) {
                String targetEmail = contact.getContactUser().getEmail();
                String emailBody = "Level: " + level + "\nLocation: " + location + "\nLive tracking link: " + liveTrackingUrl;
                
                System.out.println("[DEBUG-SOS] Attempting to send direct email to: " + targetEmail);
                sendEmail(targetEmail, 
                        "SOS Alert from " + user.getName(),
                        emailBody);
            } else {
                System.out.println("[DEBUG-SOS] Skipping contact " + contact.getName() + " because contactUser is null");
            }
        }


        return alert;
    }

    private void sendEmail(String to, String subject, String text) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject(subject);
            message.setText(text);
            mailSender.send(message);
            System.out.println("[DEBUG-SOS] Email successfully sent to: " + to);
        } catch (Exception e) {
            System.err.println("[ERROR-MAIL] Failed to send email to " + to + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public List<Alert> getUserAlerts(Long userId) {
        return alertRepository.findByUserId(userId);
    }
}
