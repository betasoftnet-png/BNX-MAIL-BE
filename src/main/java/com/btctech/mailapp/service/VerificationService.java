package com.btctech.mailapp.service;

import com.btctech.mailapp.dto.cashfree.CashfreeCreateUrlResponse;
import com.btctech.mailapp.dto.cashfree.CashfreeStatusResponse;
import com.btctech.mailapp.entity.VerificationSession;
import com.btctech.mailapp.repository.VerificationSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationService {

    private final CashfreeService cashfreeService;
    private final VerificationSessionRepository sessionRepository;
    private final MailboxService mailboxService;
    private final MastersIndiaGstService mastersIndiaGstService;
    private final com.btctech.mailapp.repository.UserRepository userRepository;
    private final com.btctech.mailapp.repository.BusinessProfileRepository businessProfileRepository;

    @Value("${app.frontend.redirect-url:https://www.b2auth.com/}")
    private String frontendRedirectUrl;

    @Transactional
    public String initiateVerification(Long userId, Long mailAccountId) {
        String referenceId = "VER_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        CashfreeCreateUrlResponse cfResponse = cashfreeService.createDigiLockerUrl(referenceId, frontendRedirectUrl);

        VerificationSession session = new VerificationSession();
        session.setUserId(userId);
        session.setMailAccountId(mailAccountId);
        session.setReferenceId(referenceId);
        session.setVerificationId(cfResponse.getVerificationId());
        session.setStatus("PENDING");
        sessionRepository.save(session);

        log.info("Initiated verification for mailAccountId: {} with reference: {}", mailAccountId, referenceId);
        return cfResponse.getRedirectUrl();
    }

    @Transactional
    public String checkAndFinalizeVerification(String referenceId) {
        VerificationSession session = sessionRepository.findByReferenceId(referenceId)
                .orElseThrow(() -> new RuntimeException("Verification session not found"));

        // Use case-insensitive check for existing status
        String currentStatus = session.getStatus() != null ? session.getStatus().toUpperCase() : "";
        if (currentStatus.equals("AUTHENTICATED") || currentStatus.equals("VERIFIED") || currentStatus.equals("SUCCESS")) {
            return "SUCCESS";
        }

        CashfreeStatusResponse cfStatus = cashfreeService.getVerificationStatus(session.getVerificationId());
        
        String cfStatusValue = cfStatus.getVerificationStatus();
        String cfReqStatus = cfStatus.getStatus();
        
        log.info("Cashfree status check for {}: verification_status={}, status={}", 
            referenceId, cfStatusValue, cfReqStatus);
        
        String finalStatus = (cfStatusValue != null) ? cfStatusValue : cfReqStatus;
        if (finalStatus == null) finalStatus = "PENDING";

        session.setStatus(finalStatus);
        sessionRepository.saveAndFlush(session);

        String upperStatus = finalStatus.toUpperCase();
        if (upperStatus.equals("AUTHENTICATED") || upperStatus.equals("VERIFIED") || upperStatus.equals("SUCCESS")) {
            finalizePromotion(session);
            return "SUCCESS";
        }

        return finalStatus;
    }

    private void finalizePromotion(VerificationSession session) {
        log.info("Promoting email account {} to primary for user {}", session.getMailAccountId(), session.getUserId());
        
        try {
            com.btctech.mailapp.dto.cashfree.CashfreeDigilockerDocumentResponse docResponse = cashfreeService.getDigilockerDocument(session.getVerificationId());
            if (docResponse != null && docResponse.getDocument() != null) {
                com.btctech.mailapp.entity.User user = userRepository.findById(session.getUserId())
                        .orElseThrow(() -> new RuntimeException("User not found"));
                        
                String aadhaarName = docResponse.getDocument().getName();
                String aadhaarNumber = docResponse.getDocument().getDocumentNumber();
                
                // Mask Aadhaar number (keep last 4 digits)
                if (aadhaarNumber != null && aadhaarNumber.length() >= 4) {
                    aadhaarNumber = "XXXXXXXX" + aadhaarNumber.substring(aadhaarNumber.length() - 4);
                }
                
                user.setAadhaarName(aadhaarName);
                user.setAadhaarNumber(aadhaarNumber);
                userRepository.save(user);
                
                log.info("Saved verified Aadhaar details for user {}", session.getUserId());
            }
        } catch (Exception e) {
            log.error("Failed to fetch or save DigiLocker document for session {}: {}", session.getReferenceId(), e.getMessage());
            // Proceed with promotion even if document fetch fails? Or fail? The user said "After successful verification, retrieve...".
            // Let's assume we proceed with promotion if status was SUCCESS.
        }
        
        mailboxService.setPrimaryEmail(session.getUserId(), session.getMailAccountId());
    }

    @Transactional
    public boolean verifyPanAndFinalize(Long userId, Long mailAccountId, String pan, String name, String gstin) {
        log.info("Initiating verification for user {} and mailAccountId {}", userId, mailAccountId);
        
        // 1. Check Uniqueness
        java.util.Optional<com.btctech.mailapp.entity.User> existingUserOpt = userRepository.findByPanNumber(pan);
        if (existingUserOpt.isPresent()) {
            if (!existingUserOpt.get().getId().equals(userId)) {
                log.warn("PAN Verification FAILED: PAN {} is already registered to another user", pan);
                throw new RuntimeException("This PAN is already registered to another account.");
            }
        }

        com.btctech.mailapp.entity.User currentUser = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));

        if (currentUser.getAccountType() == com.btctech.mailapp.entity.AccountType.BUSINESS) {
            if (gstin == null || gstin.trim().isEmpty()) {
                throw new RuntimeException("GSTIN is required for business accounts.");
            }

            java.util.Optional<com.btctech.mailapp.entity.User> existingGstUserOpt = userRepository.findByGstin(gstin);
            if (existingGstUserOpt.isPresent() && !existingGstUserOpt.get().getId().equals(userId)) {
                log.warn("GSTIN Verification FAILED: GSTIN {} is already registered to another user", gstin);
                throw new RuntimeException("This GSTIN is already registered to another account.");
            }

            // Call Cashfree PAN to GSTIN API
            String verificationId = "pan_gstin_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            com.btctech.mailapp.dto.cashfree.CashfreePanToGstinResponse cfResponse = cashfreeService.getGstinByPan(pan, verificationId);
            
            if ("SUCCESS".equalsIgnoreCase(cfResponse.getStatus()) && cfResponse.getGstinList() != null) {
                com.btctech.mailapp.dto.cashfree.GstinData matchedData = null;
                for (com.btctech.mailapp.dto.cashfree.GstinData data : cfResponse.getGstinList()) {
                    if (data.getGstin().equalsIgnoreCase(gstin) && "ACTIVE".equalsIgnoreCase(data.getStatus())) {
                        matchedData = data;
                        break;
                    }
                }
                
                if (matchedData == null) {
                    throw new RuntimeException("The provided GSTIN is not valid or not active for this PAN.");
                }

                log.info("Business Verification SUCCESS for user {}. Matched GSTIN: {}", userId, gstin);
                
                // Save details to BusinessProfile
                com.btctech.mailapp.entity.BusinessProfile profile = businessProfileRepository.findByUserId(userId)
                        .orElse(new com.btctech.mailapp.entity.BusinessProfile());
                profile.setUser(currentUser);
                
                if (matchedData.getLegalName() != null && !matchedData.getLegalName().trim().isEmpty()) {
                    profile.setBusinessName(matchedData.getLegalName());
                }
                if (matchedData.getConstitutionOfBusiness() != null) {
                    profile.setBusinessType(matchedData.getConstitutionOfBusiness());
                }
                if (profile.getBusinessName() == null || profile.getBusinessName().trim().isEmpty()) {
                    profile.setBusinessName("Organization - " + gstin);
                }
                profile.setGstin(gstin);
                businessProfileRepository.save(profile);

                currentUser.setPanNumber(pan);
                currentUser.setGstin(gstin);
                userRepository.save(currentUser);

                mailboxService.setPrimaryEmail(userId, mailAccountId);
                return true;
            } else {
                log.warn("GSTIN Verification FAILED for user {}. Reason: GSTIN_NOT_FOUND", userId);
                throw new RuntimeException("Failed to verify GSTIN for the provided PAN.");
            }
        } else {
            // Call Cashfree PAN 360 API for Public
            String verificationId = "pan_verify_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            com.btctech.mailapp.dto.cashfree.CashfreePanAdvanceResponse cfResponse = cashfreeService.verifyPanAdvance(pan, verificationId, name);
            
            if ("VALID".equalsIgnoreCase(cfResponse.getStatus())) {
                // 3. Name Matching Rule (Basic)
                String registeredName = cfResponse.getRegisteredName() != null ? cfResponse.getRegisteredName() : cfResponse.getNamePanCard();
                if (registeredName != null && name != null) {
                    String normalizedProvided = name.replaceAll("\\s+", "").toLowerCase();
                    String normalizedRegistered = registeredName.replaceAll("\\s+", "").toLowerCase();
                    
                    if (!normalizedRegistered.contains(normalizedProvided) && !normalizedProvided.contains(normalizedRegistered)) {
                        log.warn("PAN Verification FAILED for user {}. Name mismatch. Provided: {}, Registered: {}", userId, name, registeredName);
                        throw new RuntimeException("Name mismatch. Please ensure the name matches your PAN card.");
                    }
                }

                log.info("PAN Verification SUCCESS for user {}. Updating PAN and promoting email...", userId);
                
                currentUser.setPanNumber(pan);
                userRepository.save(currentUser);

                mailboxService.setPrimaryEmail(userId, mailAccountId);
                return true;
            } else {
                log.warn("PAN Verification FAILED for user {}. Reason: {}", userId, cfResponse.getMessage());
                return false;
            }
        }
    }

    public java.util.List<com.btctech.mailapp.dto.cashfree.GstinData> fetchActiveGstins(String pan) {
        String verificationId = "pan_gstin_fetch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 15);
        com.btctech.mailapp.dto.cashfree.CashfreePanToGstinResponse cfResponse = cashfreeService.getGstinByPan(pan, verificationId);
        
        java.util.List<com.btctech.mailapp.dto.cashfree.GstinData> activeGstins = new java.util.ArrayList<>();
        if ("SUCCESS".equalsIgnoreCase(cfResponse.getStatus()) && cfResponse.getGstinList() != null) {
            for (com.btctech.mailapp.dto.cashfree.GstinData data : cfResponse.getGstinList()) {
                if ("ACTIVE".equalsIgnoreCase(data.getStatus())) {
                    activeGstins.add(data);
                }
            }
        }
        return activeGstins;
    }

    @Transactional
    public boolean verifyGstAndFinalize(Long userId, Long mailAccountId, String gstin) {
        log.info("Initiating Masters India GST verification for user {} and mailAccountId {}", userId, mailAccountId);

        java.util.Optional<com.btctech.mailapp.entity.User> existingGstUserOpt = userRepository.findByGstin(gstin);
        if (existingGstUserOpt.isPresent() && !existingGstUserOpt.get().getId().equals(userId)) {
            log.warn("GST Verification FAILED: GSTIN {} is already registered to another user", gstin);
            throw new RuntimeException("This GSTIN is already registered to another account.");
        }

        com.btctech.mailapp.entity.User currentUser = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (currentUser.getAccountType() != com.btctech.mailapp.entity.AccountType.BUSINESS) {
            throw new RuntimeException("GST Verification is only for Business accounts.");
        }

        if (gstin == null || gstin.trim().isEmpty()) {
            throw new RuntimeException("GSTIN is required.");
        }

        // Call Masters India GST API
        com.btctech.mailapp.dto.mastersindia.MastersIndiaGstResponse response = mastersIndiaGstService.verifyGstin(gstin);

        if (response.getData() != null && "Active".equalsIgnoreCase(response.getData().getSts())) {
            log.info("GST Verification SUCCESS for user {}.", userId);
            
            // Save details to BusinessProfile
            com.btctech.mailapp.entity.BusinessProfile profile = businessProfileRepository.findByUserId(userId)
                    .orElse(new com.btctech.mailapp.entity.BusinessProfile());
            profile.setUser(currentUser);
            if (response.getData().getLgnm() != null && (profile.getBusinessName() == null || profile.getBusinessName().isEmpty())) {
                profile.setBusinessName(response.getData().getLgnm());
            }
            if (response.getData().getDty() != null) {
                profile.setBusinessType(response.getData().getDty());
            }
            if (response.getData().getPradr() != null && response.getData().getPradr().getAddr() != null) {
                com.btctech.mailapp.dto.mastersindia.MastersIndiaGstResponse.Address addr = response.getData().getPradr().getAddr();
                StringBuilder addrStr = new StringBuilder();
                if (addr.getBnm() != null) addrStr.append(addr.getBnm()).append(", ");
                if (addr.getSt() != null) addrStr.append(addr.getSt()).append(", ");
                if (addr.getLoc() != null) addrStr.append(addr.getLoc()).append(", ");
                if (addr.getCity() != null) addrStr.append(addr.getCity()).append(", ");
                if (addr.getDst() != null) addrStr.append(addr.getDst()).append(", ");
                if (addr.getStcd() != null) addrStr.append(addr.getStcd()).append(" ");
                if (addr.getPncd() != null) addrStr.append("- ").append(addr.getPncd());
                
                String finalAddr = addrStr.toString().trim();
                if (finalAddr.endsWith(",")) finalAddr = finalAddr.substring(0, finalAddr.length() - 1);
                profile.setBusinessAddress(finalAddr);
            }
            profile.setGstin(gstin);
            businessProfileRepository.save(profile);

            // Save GSTIN in user
            currentUser.setGstin(gstin); // Store GSTIN
            userRepository.save(currentUser);

            mailboxService.setPrimaryEmail(userId, mailAccountId);

            return true;
        } else {
            throw new RuntimeException("The provided GSTIN is not active or invalid.");
        }
    }
}
