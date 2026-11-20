package com.btctech.mailapp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "read_receipts", indexes = {
    @Index(name = "idx_rr_receipt_id", columnList = "receipt_id", unique = true),
    @Index(name = "idx_rr_sender", columnList = "sender_email"),
    @Index(name = "idx_rr_recipient", columnList = "recipient_email"),
    @Index(name = "idx_rr_send_id", columnList = "send_id"),
    @Index(name = "idx_rr_status", columnList = "status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReadReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "receipt_id", nullable = false, unique = true, length = 64)
    private String receiptId;

    @Column(name = "sender_email", nullable = false, length = 150)
    private String senderEmail;

    @Column(name = "sender_user_id")
    private Long senderUserId;

    @Column(name = "recipient_email", nullable = false, length = 255)
    private String recipientEmail;

    @Column(length = 500)
    private String subject;

    @Column(name = "message_id", length = 255)
    private String messageId;

    @Column(name = "send_id", length = 64)
    private String sendId;

    @Column(name = "sent_uid", length = 100)
    private String sentUid;

    /**
     * Statuses: PENDING, READ, DECLINED, UNSUPPORTED, FAILED
     */
    @Column(nullable = false, length = 32)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "requested_at", nullable = false)
    @Builder.Default
    private LocalDateTime requestedAt = LocalDateTime.now();

    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    @Column(name = "recipient_user_agent", length = 255)
    private String recipientUserAgent;

    @Column(name = "declined_reason", length = 255)
    private String declinedReason;

    @Version
    private Long version;

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();
}
