package com.btctech.mailapp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.time.LocalDate;

@Data
public class RegisterRequest {
    @NotBlank(message = "Username is required")
    private String username;

    @NotBlank(message = "Password is required")
    private String password;

    private String phoneNumber;

    // Common fields
    private String firstName;
    private String lastName;

    @NotBlank(message = "Mode is required")
    private String mode; // PERSONAL, BUSINESS

    // Personal/Child fields
    private LocalDate dob;
    private String parentEmail;

    // Business fields
    private String businessName;
    private String businessType;
    private String registrationNumber;
    private String ownerFirstName;
    private String ownerLastName;
    private String domain; // Required for business to set up org

    // Primary Business flow fields
    private String businessFlow; // 'primary' or 'secondary'
    private String businessSize; // 'small' or 'large'
    private String industry;
    private String gstin;

    // Detailed onboarding fields
    private String companySize;
    private String businessWebsite;
    private String businessAddress;
}
