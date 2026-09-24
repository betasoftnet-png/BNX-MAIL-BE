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
    name = "contact_aliases",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_owner_contact_user", columnNames = {"owner_user_id", "contact_user_id"})
    },
    indexes = {
        @Index(name = "idx_alias_owner", columnList = "owner_user_id"),
        @Index(name = "idx_alias_contact", columnList = "contact_user_id")
    }
)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContactAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "contact_user_id", nullable = false)
    private Long contactUserId;

    @Column(name = "custom_name", nullable = false, length = 150)
    private String customName;

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
