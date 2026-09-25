package com.btctech.mailapp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "processed_emails", uniqueConstraints = {
    @UniqueConstraint(name = "uk_user_message_identifier", columnNames = {"user_email", "message_identifier"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedEmail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_email", nullable = false, length = 150)
    private String userEmail;

    @Column(name = "message_identifier", nullable = false, length = 255)
    private String messageIdentifier;

    @Column(name = "sender_email", length = 150)
    private String senderEmail;

    @Column(name = "subject", length = 255)
    private String subject;

    @Column(name = "folder", length = 50)
    private String folder;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    @PrePersist
    protected void onCreate() {
        if (this.processedAt == null) {
            this.processedAt = LocalDateTime.now();
        }
    }
}
