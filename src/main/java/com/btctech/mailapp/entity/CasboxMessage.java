package com.btctech.mailapp.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "casbox_messages", indexes = {
    @Index(name = "idx_casbox_sender", columnList = "sender_email"),
    @Index(name = "idx_casbox_receiver", columnList = "receiver_email"),
    @Index(name = "idx_casbox_timestamp", columnList = "timestamp"),
    @Index(name = "idx_casbox_sender_receiver", columnList = "sender_email, receiver_email"),
    @Index(name = "idx_casbox_receiver_status", columnList = "receiver_email, status")
})
public class CasboxMessage {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sender_email", nullable = false)
    private String senderEmail;

    @Column(name = "receiver_email", nullable = false)
    private String receiverEmail;

    @Column(name = "subject")
    private String subject;

    @Column(name = "body", columnDefinition = "LONGTEXT")
    private String body;

    @Column(name = "attachments_json", columnDefinition = "LONGTEXT")
    private String attachmentsJson;

    // SENT, DELIVERED, SEEN
    @Column(name = "status", nullable = false)
    private String status = "SENT";

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp = LocalDateTime.now();

    @Column(name = "sender_archived", nullable = false)
    private Boolean senderArchived = false;

    @Column(name = "receiver_archived", nullable = false)
    private Boolean receiverArchived = false;

    @Column(name = "is_edited", nullable = false)
    private Boolean isEdited = false;

    public boolean isSenderArchived() {
        return senderArchived != null && senderArchived;
    }

    public boolean isReceiverArchived() {
        return receiverArchived != null && receiverArchived;
    }

    public boolean isEdited() {
        return isEdited != null && isEdited;
    }
}
