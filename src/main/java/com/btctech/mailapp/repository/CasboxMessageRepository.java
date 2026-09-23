package com.btctech.mailapp.repository;

import com.btctech.mailapp.entity.CasboxMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CasboxMessageRepository extends JpaRepository<CasboxMessage, Long> {
    
    @Query("SELECT m FROM CasboxMessage m WHERE (m.senderEmail = :email1 AND m.receiverEmail = :email2) OR (m.senderEmail = :email2 AND m.receiverEmail = :email1) ORDER BY m.timestamp ASC")
    List<CasboxMessage> findConversation(@Param("email1") String email1, @Param("email2") String email2);

    @Query("SELECT m FROM CasboxMessage m WHERE m.senderEmail = :userEmail OR m.receiverEmail = :userEmail ORDER BY m.timestamp DESC")
    List<CasboxMessage> findAllMessagesForUser(@Param("userEmail") String userEmail);

    @Query("SELECT m FROM CasboxMessage m WHERE m.receiverEmail = :receiverEmail AND m.status != 'SEEN'")
    List<CasboxMessage> findUnseenMessagesForUser(@Param("receiverEmail") String receiverEmail);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("DELETE FROM CasboxMessage m WHERE (LOWER(TRIM(m.senderEmail)) = LOWER(TRIM(:email1)) AND LOWER(TRIM(m.receiverEmail)) = LOWER(TRIM(:email2))) OR (LOWER(TRIM(m.senderEmail)) = LOWER(TRIM(:email2)) AND LOWER(TRIM(m.receiverEmail)) = LOWER(TRIM(:email1)))")
    void deleteConversation(@Param("email1") String email1, @Param("email2") String email2);
}
