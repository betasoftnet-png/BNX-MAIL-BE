package com.btctech.mailapp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "biometric_credentials", indexes = {
        @Index(name = "idx_bio_cred_id", columnList = "credential_id"),
        @Index(name = "idx_bio_user", columnList = "user_id")
})
public class BiometricCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "credential_id", nullable = false, unique = true, length = 512)
    private String credentialId;

    @Lob
    @Column(name = "public_key_cose", nullable = false, columnDefinition = "TEXT")
    private String publicKeyCose;

    @Builder.Default
    @Column(name = "algorithm", nullable = false)
    private Integer algorithm = -7; // -7 for ES256, -257 for RS256

    @Builder.Default
    @Column(name = "sign_count", nullable = false)
    private Long signCount = 0L;

    @Column(name = "device_name", length = 150)
    private String deviceName;

    @Column(name = "transports", length = 100)
    private String transports;

    @Column(name = "aaguid", length = 64)
    private String aaguid;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Builder.Default
    @Column(name = "revoked", nullable = false)
    private Boolean revoked = false;
}

