package com.btctech.mailapp.repository;

import com.btctech.mailapp.entity.BiometricCredential;
import com.btctech.mailapp.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BiometricCredentialRepository extends JpaRepository<BiometricCredential, Long> {
    List<BiometricCredential> findByUserAndRevokedFalse(User user);
    Optional<BiometricCredential> findByCredentialIdAndRevokedFalse(String credentialId);
    Optional<BiometricCredential> findByCredentialId(String credentialId);
    void deleteByUser(User user);
    long countByUserAndRevokedFalse(User user);
}

