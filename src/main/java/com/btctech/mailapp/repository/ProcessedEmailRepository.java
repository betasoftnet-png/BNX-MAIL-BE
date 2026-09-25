package com.btctech.mailapp.repository;

import com.btctech.mailapp.entity.ProcessedEmail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProcessedEmailRepository extends JpaRepository<ProcessedEmail, Long> {
    boolean existsByUserEmailAndMessageIdentifier(String userEmail, String messageIdentifier);
    Optional<ProcessedEmail> findByUserEmailAndMessageIdentifier(String userEmail, String messageIdentifier);
}
