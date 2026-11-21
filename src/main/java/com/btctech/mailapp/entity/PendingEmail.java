package com.btctech.mailapp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "pending_emails", indexes = {
    @Index(name = "idx_pending_send_id", columnList = "send_id", unique = true),
    @Index(name = "idx_pending_user_status", columnList = "user_email, status"),
    @Index(name = "idx_pending_status_deadline", columnList = "status, undo_deadline")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingEmail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "send_id", nullable = false, unique = true, length = 64)
    private String sendId;

    @Column(name = "user_email", nullable = false, length = 150)
    private String userEmail;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "mail_account_id")
    private Long mailAccountId;

    @Column(name = "encrypted_password", columnDefinition = "TEXT")
    private String encryptedPassword;

    @Column(name = "to_recipient", nullable = false, columnDefinition = "TEXT")
    private String toRecipient;

    @Column(name = "cc_recipient", columnDefinition = "TEXT")
    private String cc;

    @Column(name = "bcc_recipient", columnDefinition = "TEXT")
    private String bcc;

    @Column(nullable = false, length = 500)
    private String subject;

    @Column(columnDefinition = "LONGTEXT", nullable = false)
    private String body;

    @Column(name = "from_name")
    private String fromName;

    @Column(name = "is_html")
    @Builder.Default
    private Boolean isHtml = true;

    @Column(name = "attachments_json", columnDefinition = "LONGTEXT")
    private String attachmentsJson;

    @Column(name = "draft_uid", length = 100)
    private String draftUid;

    @Column(name = "draft_id")
    private Long draftId;

    @Column(name = "is_starred")
    @Builder.Default
    private Boolean isStarred = false;

    @Column(name = "request_read_receipt")
    @Builder.Default
    private Boolean requestReadReceipt = false;

    @Column(name = "read_receipt_id", length = 64)
    private String readReceiptId;

    /**
     * States: PENDING_UNDO, SENDING, SENT, CANCELLED, FAILED
     */
    @Column(nullable = false, length = 32)
    @Builder.Default
    private String status = "PENDING_UNDO";

    @Column(name = "undo_deadline")
    private LocalDateTime undoDeadline;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "sent_uid", length = 100)
    private String sentUid;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Builder.Default
    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Version
    private Long version;

    /**
     * Compatibility getter/setter for scheduled delivery time
     */
    public LocalDateTime getScheduledAt() {
        return undoDeadline;
    }

    public void setScheduledAt(LocalDateTime scheduledAt) {
        this.undoDeadline = scheduledAt;
    }
}
