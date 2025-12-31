package com.example.demo.controller;

import com.example.demo.model.User;
import com.example.demo.security.CurrentUser;
import com.example.demo.service.LiveLocationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/locations")
public class LiveLocationController {

    private final LiveLocationService liveLocationService;
    private final CurrentUser currentUser;

    public LiveLocationController(LiveLocationService liveLocationService, CurrentUser currentUser) {
        this.liveLocationService = liveLocationService;
        this.currentUser = currentUser;
    }

    // Frontend sends frequent GPS updates
    @PostMapping("/update")
    public ResponseEntity<String> updateLocation(@RequestParam double latitude,
                                                 @RequestParam double longitude) {
        User user = currentUser.get();
        liveLocationService.sendLocation(user, latitude, longitude);
        return ResponseEntity.ok("Location sent");
    }
}
