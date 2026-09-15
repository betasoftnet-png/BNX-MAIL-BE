package com.btctech.mailapp.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(unique = true, nullable = false, length = 50)
    private String username;

    @Column(unique = true, nullable = true)
    private String email;
    
    @Column(name = "recovery_email", length = 255)
    private String recoveryEmail;
    
    @Column(name = "phone_number", length = 50)
    private String phoneNumber;
    
    @Column(nullable = false)
    private String password;
    
    @Column(name = "first_name", length = 100)
    private String firstName;
    
    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(length = 20)
    private String role = "USER";

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", length = 20)
    private AccountType accountType = AccountType.PUBLIC;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_user_id")
    private User parent;

    @Column(name = "is_sub_id")
    private Boolean isSubId = false;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_permissions", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "permission_id")
    private java.util.List<Integer> permissions = new java.util.ArrayList<>();

    @Column(name = "dob")
    private java.time.LocalDate dob;

    private Boolean active = true;

    private Boolean approved = true;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private java.time.LocalDateTime approvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "last_login")
    private LocalDateTime lastLogin;

    @Column(name = "is_primary")
    private Boolean isPrimary = false;

    @Column(name = "two_factor_enabled")
    private Boolean twoFactorEnabled = false;

    @Column(name = "two_factor_secret")
    private String twoFactorSecret;

    @Lob
    @Column(name = "profile_picture", columnDefinition = "LONGTEXT")
    private String profilePicture;

    @Column(name = "nickname", length = 100)
    private String nickname;

    @Column(name = "display_name", length = 150)
    private String displayName;

    @Column(name = "gender", length = 50)
    private String gender;

    @Column(name = "home_address", length = 500)
    private String homeAddress;

    @Column(name = "work_address", length = 500)
    private String workAddress;

    @Column(name = "occupation", length = 200)
    private String occupation;

    @Column(name = "bio", length = 1000)
    private String bio;
    
    @Column(name = "pan_number", length = 20, unique = true)
    private String panNumber;

    @Column(name = "aadhaar_number", length = 20)
    private String aadhaarNumber;

    @Column(name = "aadhaar_name", length = 150)
    private String aadhaarName;

    @Column(name = "gstin", length = 30, unique = true)
    private String gstin;

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}