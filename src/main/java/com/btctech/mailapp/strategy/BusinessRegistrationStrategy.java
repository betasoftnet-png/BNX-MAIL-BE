package com.btctech.mailapp.strategy;

import com.btctech.mailapp.dto.RegisterRequest;
import com.btctech.mailapp.entity.AccountType;
import com.btctech.mailapp.entity.BusinessProfile;
import com.btctech.mailapp.entity.Organization;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.repository.BusinessProfileRepository;
import com.btctech.mailapp.repository.UserRepository;
import com.btctech.mailapp.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BusinessRegistrationStrategy implements RegistrationStrategy {

    private final UserRepository userRepository;
    private final OrganizationService organizationService;
    private final BusinessProfileRepository businessProfileRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public User register(RegisterRequest request) {
        String username = request.getUsername();
        if (username == null || username.length() < 10) {
            throw new com.btctech.mailapp.exception.MailException("Business username must be at least 10 characters long");
        }

        long letters = username.chars().filter(Character::isLetter).count();
        long digits = username.chars().filter(Character::isDigit).count();

        if (letters < 7 || digits < 3) {
            throw new com.btctech.mailapp.exception.MailException("Business username must contain at least 7 letters and 3 numbers");
        }

        // 1. Determine Domain and Get/Create Organization
        String domain = request.getDomain();
        if (domain == null || domain.trim().isEmpty()) {
            domain = "bnxmail.com";
        }

        Organization org;
        try {
            // Check if organization for this domain already exists
            org = organizationService.getByDomain(domain);
        } catch (com.btctech.mailapp.exception.MailException e) {
            // Create new organization if it doesn't exist
            org = organizationService.createOrganization(
                    request.getBusinessName(),
                    domain
            );
        }

        // 2. Create User (Owner)
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setFirstName(request.getOwnerFirstName());
        user.setLastName(request.getOwnerLastName());
        user.setPhoneNumber(request.getPhoneNumber());
        user.setAccountType(AccountType.BUSINESS);
        user.setOrganization(org);
        user.setRole("ORG_ADMIN");
        user.setApproved(true);
        user.setActive(true);

        user = userRepository.save(user);

        // 3. Create Business Profile
        BusinessProfile profile = new BusinessProfile();
        profile.setUser(user);
        profile.setBusinessName(request.getBusinessName());
        profile.setBusinessType(request.getBusinessType());
        profile.setRegistrationNumber(request.getRegistrationNumber());
        
        // Primary vs Secondary Flow
        if ("primary".equalsIgnoreCase(request.getBusinessFlow())) {
            profile.setBusinessFlow("primary");
            // Set the detailed onboarding fields that were collected upfront
            profile.setBusinessType(request.getBusinessType());
            profile.setCompanySize(request.getCompanySize());
            profile.setIndustry(request.getIndustry());
            profile.setBusinessWebsite(request.getBusinessWebsite());
            profile.setBusinessAddress(request.getBusinessAddress());
            
            String gstin = request.getGstin();
            if (gstin != null && gstin.trim().isEmpty()) {
                gstin = null;
            }
            profile.setGstin(gstin);

            // No temporary login for primary; they provide all details upfront
            profile.setOnboarded(true);
            
            // Also store gstin on User entity for generic verification if needed
            if (gstin != null) {
                user.setGstin(gstin);
                userRepository.save(user);
            }
        } else {
            profile.setBusinessFlow("secondary");
            profile.setOnboarded(false);
        }

        try {
            businessProfileRepository.save(profile);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new com.btctech.mailapp.exception.MailException("A business account with these verification details (GSTIN) already exists.");
        }

        return user;
    }

    @Override
    public String getMode() {
        return "BUSINESS";
    }
}
