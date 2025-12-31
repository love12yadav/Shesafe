package com.example.demo.controller;

import com.example.demo.model.Alert;
import com.example.demo.model.User;
import com.example.demo.security.CurrentUser;
import com.example.demo.service.AlertService;
import com.example.demo.service.SosSessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/alerts")
@CrossOrigin(origins = "http://localhost:5173")
public class AlertController {

    private final AlertService alertService;
    private final CurrentUser currentUser;
    private final SosSessionService sosSessionService;

    public AlertController(AlertService alertService, CurrentUser currentUser, SosSessionService sosSessionService) {
        this.alertService = alertService;
        this.currentUser = currentUser;
        this.sosSessionService = sosSessionService;
    }

    // DTO to capture JSON body
    public static class AlertRequest {
        private String level;
        private String location;
        private double latitude;
        private double longitude;
        // Getters and Setters
        public String getLevel() { return level; }
        public void setLevel(String level) { this.level = level; }
        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
        public double getLatitude() { return latitude; }
        public void setLatitude(double latitude) { this.latitude = latitude; }
        public double getLongitude() { return longitude; }
        public void setLongitude(double longitude) { this.longitude = longitude; }
    }

    @PostMapping
    public ResponseEntity<Alert> sendAlert(@RequestBody AlertRequest request) {
        User user = currentUser.get();

        Alert alert = alertService.sendAlert(
                user.getId(),
                request.getLevel(),
                request.getLocation(),
                request.getLatitude(),
                request.getLongitude()
        );

        sosSessionService.startSession(user);
        return ResponseEntity.ok(alert);
    }

    @PostMapping("/stop")
    public ResponseEntity<String> stopSOS() {
        User user = currentUser.get();
        sosSessionService.stopSession(user);
        return ResponseEntity.ok("SOS stopped");
    }

    @GetMapping
    public ResponseEntity<List<Alert>> getAlerts() {
        User user = currentUser.get();
        return ResponseEntity.ok(alertService.getUserAlerts(user.getId()));
    }
}