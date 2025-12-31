package com.example.demo.controller;

import com.example.demo.model.TrustedContact;
import com.example.demo.model.User;
import com.example.demo.security.CurrentUser;
import com.example.demo.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;
    private final CurrentUser currentUser;

    public UserController(UserService userService, CurrentUser currentUser) {
        this.userService = userService;
        this.currentUser = currentUser;
    }

    @PostMapping("/contacts")
    public ResponseEntity<?> addContact(@RequestBody Map<String, String> payload) {
        String email = payload.get("email");
        User user = currentUser.get();
        TrustedContact saved = userService.addContact(user, email);
        return ResponseEntity.ok(saved); // Return the full object for better frontend feedback
    }

    @GetMapping("/contacts")
    public ResponseEntity<List<TrustedContact>> getContacts() {
        User user = currentUser.get();
        List<TrustedContact> contacts = userService.getContacts(user);
        return ResponseEntity.ok(contacts);
    }
    @GetMapping("/me")
    public ResponseEntity<User> getCurrentUser() {
        User user = currentUser.get();
        return ResponseEntity.ok(user);
    }
}