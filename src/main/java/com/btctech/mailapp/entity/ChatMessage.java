package com.btctech.mailapp.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "messages", indexes = {
    @Index(name = "idx_messages_chat_timestamp", columnList = "chat_id, timestamp"),
    @Index(name = "idx_messages_chat_id", columnList = "chat_id")
})
public class ChatMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_id", nullable = false)
    private Chat chat;

    @Column(name = "sender_email", nullable = false)
    private String senderEmail;

    @Column(name = "content", nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    @Column(name = "attachments_json", columnDefinition = "LONGTEXT")
    private String attachmentsJson;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp = LocalDateTime.now();
}
