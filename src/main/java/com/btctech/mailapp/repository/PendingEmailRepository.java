package com.btctech.mailapp.repository;

import com.btctech.mailapp.entity.PendingEmail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PendingEmailRepository extends JpaRepository<PendingEmail, Long> {

    Optional<PendingEmail> findBySendId(String sendId);

    Optional<PendingEmail> findBySendIdAndUserEmail(String sendId, String userEmail);

    List<PendingEmail> findByUserEmailAndStatus(String userEmail, String status);

    List<PendingEmail> findByStatusAndScheduledAtBefore(String status, LocalDateTime time);

    @Modifying
    @Query("UPDATE PendingEmail p SET p.status = :newStatus, p.updatedAt = :now WHERE p.sendId = :sendId AND p.status = :expectedStatus")
    int updateStatusIfCurrentStatus(
            @Param("sendId") String sendId,
            @Param("expectedStatus") String expectedStatus,
            @Param("newStatus") String newStatus,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query("UPDATE PendingEmail p SET p.status = :newStatus, p.sentUid = :sentUid, p.sentAt = :now, p.updatedAt = :now WHERE p.sendId = :sendId")
    int markSent(
            @Param("sendId") String sendId,
            @Param("newStatus") String newStatus,
            @Param("sentUid") String sentUid,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query("UPDATE PendingEmail p SET p.status = :newStatus, p.errorMessage = :errorMessage, p.updatedAt = :now WHERE p.sendId = :sendId")
    int markFailed(
            @Param("sendId") String sendId,
            @Param("newStatus") String newStatus,
            @Param("errorMessage") String errorMessage,
            @Param("now") LocalDateTime now
    );
}
