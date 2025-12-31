package com.example.demo.model;

import jakarta.persistence.*;

@Entity
@Table(name = "trusted_contacts")
public class TrustedContact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String phone;
    private String email;

    // Owner of this trusted contact
    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    // The actual registered user who will receive alerts
    @ManyToOne
    @JoinColumn(name = "contact_user_id")
    private User contactUser;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public User getContactUser() { return contactUser; }
    public void setContactUser(User contactUser) { this.contactUser = contactUser; }
}
