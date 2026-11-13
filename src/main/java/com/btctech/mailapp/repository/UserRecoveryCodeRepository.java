package com.btctech.mailapp.repository;

import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.entity.UserRecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserRecoveryCodeRepository extends JpaRepository<UserRecoveryCode, Long> {
    List<UserRecoveryCode> findByUserAndUsedFalse(User user);
    List<UserRecoveryCode> findByUser(User user);
    void deleteByUser(User user);
}

