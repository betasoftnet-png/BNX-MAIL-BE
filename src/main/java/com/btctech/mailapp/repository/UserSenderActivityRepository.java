package com.btctech.mailapp.repository;

import com.btctech.mailapp.entity.UserSenderActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserSenderActivityRepository extends JpaRepository<UserSenderActivity, Long> {

    Optional<UserSenderActivity> findByUserEmailAndSenderEmail(String userEmail, String senderEmail);

    List<UserSenderActivity> findByUserEmail(String userEmail);
}
