package com.btctech.mailapp.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "external_app_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExternalAppSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_app_id", nullable = false)
    private ClientApp clientApp;

    @Column(name = "logged_in_at")
    private LocalDateTime loggedInAt;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "location")
    private String location;

    @Column(name = "latitude")
    private String latitude;

    @Column(name = "longitude")
    private String longitude;

    @Builder.Default
    @Column(name = "revoked")
    private Boolean revoked = false;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "token_jti")
    private String tokenJti;

    @PrePersist
    protected void onCreate() {
        if (loggedInAt == null) {
            loggedInAt = LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        }
        if (revoked == null) {
            revoked = false;
        }
    }
}
