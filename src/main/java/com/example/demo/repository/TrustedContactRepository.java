package com.example.demo.repository;

import com.example.demo.model.TrustedContact;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TrustedContactRepository extends JpaRepository<TrustedContact, Long> {
    List<TrustedContact> findByUserId(Long userId);
}
