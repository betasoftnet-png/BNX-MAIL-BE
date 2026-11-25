package com.btctech.mailapp.repository;

import com.btctech.mailapp.entity.RefreshToken;
import com.btctech.mailapp.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    @EntityGraph(attributePaths = {"user"})
    Optional<RefreshToken> findByToken(String token);
    java.util.List<RefreshToken> findAllByUserAndRevokedFalse(User user);
    java.util.List<RefreshToken> findAllByUser(User user);
    void deleteByUser(User user);
}
