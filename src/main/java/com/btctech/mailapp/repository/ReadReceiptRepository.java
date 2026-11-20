package com.btctech.mailapp.repository;

import com.btctech.mailapp.entity.ReadReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReadReceiptRepository extends JpaRepository<ReadReceipt, Long> {
    Optional<ReadReceipt> findByReceiptId(String receiptId);
    Optional<ReadReceipt> findBySendId(String sendId);
    Optional<ReadReceipt> findByMessageId(String messageId);
    Optional<ReadReceipt> findBySentUid(String sentUid);
    List<ReadReceipt> findBySenderEmail(String senderEmail);
    List<ReadReceipt> findByRecipientEmail(String recipientEmail);
    List<ReadReceipt> findBySenderEmailAndStatus(String senderEmail, String status);
    Optional<ReadReceipt> findFirstBySenderEmailAndSubjectOrderByRequestedAtDesc(String senderEmail, String subject);
    Optional<ReadReceipt> findFirstByRecipientEmailAndSubjectOrderByRequestedAtDesc(String recipientEmail, String subject);
}
