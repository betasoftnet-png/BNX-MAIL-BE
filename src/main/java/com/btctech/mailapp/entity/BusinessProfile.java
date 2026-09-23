package com.btctech.mailapp.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "business_profiles")
public class BusinessProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "business_name", nullable = false)
    private String businessName;

    @Column(name = "business_type")
    private String businessType;

    @Column(name = "business_flow", length = 20)
    private String businessFlow; // 'primary' or 'secondary'

    @Column(name = "gstin", length = 30, unique = true)
    private String gstin;

    @Column(name = "registration_number")
    private String registrationNumber;

    @Column(name = "industry")
    private String industry;

    @Column(name = "company_size")
    private String companySize;

    @Column(name = "business_website")
    private String businessWebsite;

    @Column(name = "business_address")
    private String businessAddress;

    @Column(name = "time_zone")
    private String timeZone;

    @Column(name = "language")
    private String language;

    @Lob
    @Column(name = "company_logo", columnDefinition = "LONGTEXT")
    private String companyLogo;

    @Lob
    @Column(name = "profile_photo", columnDefinition = "LONGTEXT")
    private String profilePhoto;

    @Column(name = "accept_terms")
    private Boolean acceptTerms;

    @Column(name = "onboarded")
    private Boolean onboarded = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
