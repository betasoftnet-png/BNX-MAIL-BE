package com.btctech.mailapp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Entity
@Table(
    name = "connections",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_requester_receiver", columnNames = {"requester_id", "receiver_id"})
    },
    indexes = {
        @Index(name = "idx_conn_requester", columnList = "requester_id"),
        @Index(name = "idx_conn_receiver", columnList = "receiver_id"),
        @Index(name = "idx_conn_status", columnList = "status")
    }
)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Connection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "requester_id", nullable = false)
    private Long requesterId;

    @Column(name = "receiver_id", nullable = false)
    private Long receiverId;

    // Status: PENDING, ACCEPTED, CONNECTED, DISCONNECTED, REJECTED
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private String status = "CONNECTED";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
