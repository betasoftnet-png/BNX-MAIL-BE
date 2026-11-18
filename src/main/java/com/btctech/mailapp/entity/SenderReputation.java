package com.btctech.mailapp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "sender_reputations", uniqueConstraints = {
    @UniqueConstraint(name = "uk_user_sender_reputation", columnNames = {"user_email", "sender_email"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SenderReputation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_email", nullable = false, length = 150)
    private String userEmail;

    @Column(name = "sender_email", nullable = false, length = 150)
    private String senderEmail;

    /**
     * Reputation status:
     * TRUSTED - Explicitly trusted or restored by user from spam ("Not Spam")
     * REPORTED_SPAM - Explicitly reported as spam by user
     * BLOCKED - Blocked by user
     * UNSUBSCRIBED - Unsubscribed by user
     * NEUTRAL - Normal default
     */
    @Column(name = "status", nullable = false, length = 50)
    private String status;

    @Column(name = "notes", length = 255)
    private String notes;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onSave() {
        if (this.updatedAt == null) {
            this.updatedAt = LocalDateTime.now();
        }
    }
}
