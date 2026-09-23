package com.btctech.mailapp.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "casbox_messages")
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

    public boolean isSenderArchived() {
        return senderArchived != null && senderArchived;
    }

    public boolean isReceiverArchived() {
        return receiverArchived != null && receiverArchived;
    }
}
