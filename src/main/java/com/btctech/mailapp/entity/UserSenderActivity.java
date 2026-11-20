package com.btctech.mailapp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_sender_activity", uniqueConstraints = {
    @UniqueConstraint(name = "uk_user_sender", columnNames = {"user_email", "sender_email"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSenderActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_email", nullable = false)
    private String userEmail;

    @Column(name = "sender_email", nullable = false)
    private String senderEmail;

    @Column(name = "open_count", nullable = false)
    @Builder.Default
    private int openCount = 0;

    @Column(name = "reply_count", nullable = false)
    @Builder.Default
    private int replyCount = 0;

    @Column(name = "sent_count", nullable = false)
    @Builder.Default
    private int sentCount = 0;

    @Column(name = "thread_count", nullable = false)
    @Builder.Default
    private int threadCount = 0;

    @Column(name = "last_opened_at")
    private LocalDateTime lastOpenedAt;

    @Column(name = "last_replied_at")
    private LocalDateTime lastRepliedAt;

    @Column(name = "last_interaction_at")
    private LocalDateTime lastInteractionAt;

    @Column(name = "reputation_score")
    @Builder.Default
    private Double reputationScore = 0.0;

    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();
}
