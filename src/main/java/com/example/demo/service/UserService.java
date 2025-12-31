package com.example.demo.service;

import com.example.demo.model.TrustedContact;
import com.example.demo.model.User;
import com.example.demo.repository.TrustedContactRepository;
import com.example.demo.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final TrustedContactRepository contactRepository;

    public UserService(UserRepository userRepository, TrustedContactRepository contactRepository) {
        this.userRepository = userRepository;
        this.contactRepository = contactRepository;
    }

    @Transactional
    public TrustedContact addContact(User currentUser, String contactEmail) {
        // 1. Find the actual User entity for the email
        User contactUserEntity = userRepository.findByEmail(contactEmail)
                .orElseThrow(() -> new RuntimeException("User not found with email: " + contactEmail));

        // 2. Prevent self-add
        if (contactUserEntity.getId().equals(currentUser.getId())) {
            throw new RuntimeException("You cannot add yourself as a trusted contact.");
        }

        // 3. Create relationship
        TrustedContact newContact = new TrustedContact();
        newContact.setUser(currentUser);       // The owner
        newContact.setContactUser(contactUserEntity); // The actual user (FIXES THE NULL ISSUE)
        newContact.setName(contactUserEntity.getName());
        newContact.setEmail(contactUserEntity.getEmail());
        newContact.setPhone(contactUserEntity.getPhone());

        return contactRepository.save(newContact);
    }

    public List<TrustedContact> getContacts(User user) {
        return contactRepository.findByUserId(user.getId());
    }
}