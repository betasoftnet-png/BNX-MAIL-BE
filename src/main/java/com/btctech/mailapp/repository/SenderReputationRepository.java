package com.btctech.mailapp.repository;

import com.btctech.mailapp.entity.SenderReputation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SenderReputationRepository extends JpaRepository<SenderReputation, Long> {

    Optional<SenderReputation> findByUserEmailAndSenderEmail(String userEmail, String senderEmail);

    boolean existsByUserEmailAndSenderEmail(String userEmail, String senderEmail);

    List<SenderReputation> findByUserEmail(String userEmail);

    void deleteByUserEmailAndSenderEmail(String userEmail, String senderEmail);
}
