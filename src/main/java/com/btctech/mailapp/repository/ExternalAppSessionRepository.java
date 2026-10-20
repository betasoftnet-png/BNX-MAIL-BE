package com.btctech.mailapp.repository;

import com.btctech.mailapp.entity.ExternalAppSession;
import com.btctech.mailapp.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExternalAppSessionRepository extends JpaRepository<ExternalAppSession, Long> {
    
    @Query("SELECT s FROM ExternalAppSession s WHERE s.user = :user AND (s.revoked IS NULL OR s.revoked = false) ORDER BY s.loggedInAt DESC")
    List<ExternalAppSession> findActiveByUserOrderByLoggedInAtDesc(@Param("user") User user);

    List<ExternalAppSession> findByUserOrderByLoggedInAtDesc(User user);

    @Query("SELECT s FROM ExternalAppSession s WHERE s.user = :user AND s.clientApp.clientId = :clientId AND (s.revoked IS NULL OR s.revoked = false) ORDER BY s.loggedInAt DESC")
    List<ExternalAppSession> findActiveByUserAndClientId(@Param("user") User user, @Param("clientId") String clientId);

    Optional<ExternalAppSession> findByIdAndRevokedFalse(Long id);

    Optional<ExternalAppSession> findByTokenJti(String tokenJti);
}
