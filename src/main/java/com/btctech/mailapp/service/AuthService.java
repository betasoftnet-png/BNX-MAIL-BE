package com.btctech.mailapp.service;

import com.btctech.mailapp.config.JwtUtil;
import com.btctech.mailapp.dto.LoginResponseData;
import com.btctech.mailapp.dto.TokenRefreshRequest;
import com.btctech.mailapp.entity.MailAccount;
import com.btctech.mailapp.entity.RefreshToken;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.exception.MailException;
import com.btctech.mailapp.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import com.btctech.mailapp.dto.RecoveryOptionsResponse;
import com.btctech.mailapp.dto.SendOtpRequest;
import com.btctech.mailapp.dto.ResetPasswordRequest;
import com.btctech.mailapp.entity.PasswordResetToken;
import com.btctech.mailapp.repository.PasswordResetTokenRepository;
import com.btctech.mailapp.repository.UserRepository;
import com.btctech.mailapp.repository.MailAccountRepository;
import java.security.SecureRandom;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    @Value("${mail.from:${MAIL_FROM:${spring.mail.username:${SPRING_MAIL_USERNAME:beta@beta-softnet.com}}}}")
    private String mailFrom;

    @Value("${sms.provider.authkey:${SMS_PROVIDER_AUTHKEY:${sms.auth.key:}}}")
    private String smsAuthKey;

    @Value("${sms.provider.templateId:${SMS_PROVIDER_TEMPLATEID:${sms.template.id:}}}")
    private String smsTemplateId;

    @Value("${sms.provider.forgotPasswordTemplateId:${SMS_PROVIDER_FORGOTPASSWORDTEMPLATEID:${sms.forgot.password.template.id:}}}")
    private String smsForgotPasswordTemplateId;

    @Value("${sms.provider.senderId:${SMS_PROVIDER_SENDERID:${sms.sender.id:}}}")
    private String smsSenderId;

    private final java.util.Map<String, ParentOtpData> parentOtpCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, MobileOtpData> mobileOtpCache = new java.util.concurrent.ConcurrentHashMap<>();

    private static class ParentOtpData {
        private final String otp;
        private final java.time.LocalDateTime expiryTime;

        public ParentOtpData(String otp, int minutesToLive) {
            this.otp = otp;
            this.expiryTime = java.time.LocalDateTime.now().plusMinutes(minutesToLive);
        }

        public boolean isExpired() {
            return java.time.LocalDateTime.now().isAfter(expiryTime);
        }
    }

    private static class MobileOtpData {
        private final String otp;
        private final java.time.LocalDateTime expiryTime;

        public MobileOtpData(String otp, int minutesToLive) {
            this.otp = otp;
            this.expiryTime = java.time.LocalDateTime.now().plusMinutes(minutesToLive);
        }

        public boolean isExpired() {
            return java.time.LocalDateTime.now().isAfter(expiryTime);
        }
    }

    public void sendParentOtp(String parentEmail) {
        java.security.SecureRandom random = new java.security.SecureRandom();
        int otpValue = 100000 + random.nextInt(900000);
        String otp = String.valueOf(otpValue);

        parentOtpCache.put(parentEmail.toLowerCase().trim(), new ParentOtpData(otp, 15));

        sendParentOtpEmail(parentEmail, otp);
        log.info("✓ Parent signup OTP sent to: {}", parentEmail);
    }

    private void sendParentOtpEmail(String toAddress, String otp) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            String fromAddress = (mailFrom != null && !mailFrom.trim().isEmpty()) ? mailFrom.trim() : "beta@beta-softnet.com";
            message.setFrom(fromAddress);
            message.setTo(toAddress);
            message.setSubject("BNX Mail Parent Consent Verification");
            message.setText("Your verification code is: " + otp + "\nUse this code to verify your contact details and approve your child's BNX Mail account registration.\nThis code will expire in 15 minutes.");
            javaMailSender.send(message);
            log.info("Sent parent OTP verification email to {}", toAddress);
        } catch (Exception e) {
            log.error("Failed to send parent verification email: {}", e.getMessage());
            throw new MailException("Failed to send verification email to parent. Please try again later.");
        }
    }

    public boolean verifyParentOtp(String parentEmail, String otp) {
        String key = parentEmail.toLowerCase().trim();
        ParentOtpData data = parentOtpCache.get(key);
        if (data == null) {
            throw new MailException("OTP not found or expired");
        }
        if (data.isExpired()) {
            parentOtpCache.remove(key);
            throw new MailException("OTP has expired");
        }
        if (!data.otp.equals(otp)) {
            throw new MailException("Invalid OTP code");
        }
        parentOtpCache.remove(key);
        return true;
    }

    public void sendMobileOtp(String mobileNumber) {
        java.security.SecureRandom random = new java.security.SecureRandom();
        int otpValue = 100000 + random.nextInt(900000);
        String otp = String.valueOf(otpValue);

        mobileOtpCache.put(mobileNumber.trim(), new MobileOtpData(otp, 15));

        try {
            RestTemplate restTemplate = new RestTemplate();
            
            // Clean mobile number (remove '+', spaces, dashes, etc. - keep only digits)
            String cleanMobile = mobileNumber.replaceAll("[^0-9]", "");
            
            String url = "https://control.msg91.com/api/v5/flow";

            HttpHeaders headers = new HttpHeaders();
            headers.set("authkey", smsAuthKey);
            headers.set("Accept", "application/json");
            headers.set("Content-Type", "application/json");

            // Format JSON body according to MSG91 v5 flow API
            // NOTE: If your MSG91 template uses a different variable name than VAR1 (e.g. OTP), change it here!
            String requestBody = "{\n" +
                    "  \"template_id\": \"" + smsTemplateId + "\",\n" +
                    "  \"short_url\": \"0\",\n" +
                    "  \"recipients\": [\n" +
                    "    {\n" +
                    "      \"mobiles\": \"" + cleanMobile + "\",\n" +
                    "      \"num\": \"" + otp + "\",\n" +
                    "      \"NUM\": \"" + otp + "\",\n" +
                    "      \"VAR1\": \"" + otp + "\",\n" +
                    "      \"var1\": \"" + otp + "\",\n" +
                    "      \"OTP\": \"" + otp + "\",\n" +
                    "      \"otp\": \"" + otp + "\"\n" +
                    "    }\n" +
                    "  ]\n" +
                    "}";

            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            
            String responseBody = response.getBody();
            log.info("MSG91 Response: {}", responseBody);
            
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            if (responseBody != null) {
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(responseBody);
                if (root.has("type") && "error".equalsIgnoreCase(root.get("type").asText())) {
                    String errorMessage = root.has("message") ? root.get("message").asText() : "Unknown MSG91 Error";
                    throw new MailException(errorMessage);
                }
            }

            log.info("✓ Mobile OTP generated and sent to: {}", mobileNumber);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.error("MSG91 HTTP Error: {}", e.getResponseBodyAsString());
            try {
                com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(e.getResponseBodyAsString());
                String errorMessage = root.has("message") ? root.get("message").asText() : "SMS Provider Error";
                throw new MailException(errorMessage);
            } catch (Exception ex) {
                throw new MailException("Failed to send SMS: " + e.getStatusText());
            }
        } catch (MailException e) {
            // Rethrow our mapped exceptions so they reach the controller
            throw e;
        } catch (Exception e) {
            log.error("Failed to send MSG91 OTP to {}: {}", mobileNumber, e.getMessage());
            throw new MailException("Failed to send SMS verification code. Please try again.");
        }
    }

    public boolean verifyMobileOtp(String mobileNumber, String otp) {
        String key = mobileNumber.trim();
        MobileOtpData data = mobileOtpCache.get(key);
        if (data == null) {
            throw new MailException("OTP not found or expired");
        }
        if (data.isExpired()) {
            mobileOtpCache.remove(key);
            throw new MailException("OTP has expired");
        }
        if (!data.otp.equals(otp)) {
            throw new MailException("Invalid OTP code");
        }
        mobileOtpCache.remove(key);
        return true;
    }

    private final RefreshTokenRepository refreshTokenRepository;
    private final com.btctech.mailapp.repository.AppealRepository appealRepository;
    private final JwtUtil jwtUtil;
    private final MailboxService mailboxService;
    private final SessionService sessionService;
    private final UserService userService;
    private final UserRepository userRepository;
    private final MailAccountRepository mailAccountRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final JavaMailSender javaMailSender;
    private final com.btctech.mailapp.repository.BusinessProfileRepository businessProfileRepository;

    @Transactional
    public void submitAppeal(String email, String message) {
        User user = userService.getUserByEmailOrUsername(email);
        if (user == null) {
            throw new MailException("User not found");
        }
        
        com.btctech.mailapp.entity.Appeal appeal = new com.btctech.mailapp.entity.Appeal();
        appeal.setBannedUser(user);
        appeal.setAppealMessage(message);
        appeal.setStatus(com.btctech.mailapp.entity.Appeal.AppealStatus.PENDING);
        appealRepository.save(appeal);
    }

    /**
     * Generate username suggestions based on firstName, lastName and dob
     */
    public List<String> generateUsernameSuggestions(String firstName, String lastName, String dob, String mode) {
        List<String> suggestions = new ArrayList<>();
        if (firstName == null || firstName.trim().isEmpty()) {
            return suggestions;
        }

        String fn = firstName.trim().toLowerCase().replaceAll("[^a-z0-9]", "");
        String ln = (lastName != null) ? lastName.trim().toLowerCase().replaceAll("[^a-z0-9]", "") : "";
        
        String yearStr = "89"; // default fallback
        String fullYearStr = "1989";
        String dayStr = "01";
        String monthStr = "01";
        
        if (dob != null && !dob.trim().isEmpty()) {
            try {
                String[] parts = dob.split("-");
                if (parts.length >= 3) {
                    fullYearStr = parts[0];
                    if (fullYearStr.length() >= 4) {
                        yearStr = fullYearStr.substring(2);
                    }
                    monthStr = parts[1];
                    dayStr = parts[2];
                }
            } catch (Exception e) {
                // ignore, keep fallback
            }
        }

        // Candidates templates list
        List<String> templates = new ArrayList<>();
        templates.add(fn + yearStr); // e.g. sridharan89
        if (!ln.isEmpty()) {
            templates.add(fn + ln.substring(0, 1) + yearStr); // e.g. sridharank89
            templates.add(ln + fn + yearStr); // e.g. kumarsridharan89
        }
        templates.add(fn + fullYearStr); // e.g. sridharan1989
        templates.add(fn + dayStr + monthStr); // e.g. sridharan1205
        if (!ln.isEmpty()) {
            templates.add(fn + ln); // e.g. sridharankumar
            templates.add(fn.substring(0, 1) + ln + yearStr); // e.g. skumar89
        }

        final String finalFn = fn;
        final String finalLn = ln;
        final String finalYear = fullYearStr;
        final String finalMonth = monthStr;
        final String finalDay = dayStr;
        templates = templates.stream()
            .map(base -> enforceUsernameRules(base, finalFn, finalLn, finalYear, finalMonth, finalDay))
            .collect(Collectors.toList());

        // Filter and check database uniqueness
        for (String candidate : templates) {
            if (suggestions.size() >= 3) break;
            if (candidate.length() >= 3 && isUsernameAvailable(candidate)) {
                suggestions.add(candidate);
            }
        }

        // If we still don't have 3, append numeric suffix to first option until we get 3
        int suffix = 1;
        while (suggestions.size() < 3) {
            String candidate = fn + yearStr + suffix;
            candidate = enforceUsernameRules(candidate, fn, ln, fullYearStr, monthStr, dayStr);
            
            if (isUsernameAvailable(candidate)) {
                suggestions.add(candidate);
            }
            suffix++;
        }

        return suggestions;
    }

    private String enforceUsernameRules(String base, String fn, String ln, String year, String month, String day) {
        StringBuilder letters = new StringBuilder();
        StringBuilder digits = new StringBuilder();
        
        for (char c : base.toCharArray()) {
            if (Character.isLetter(c)) letters.append(c);
            else if (Character.isDigit(c)) digits.append(c);
        }
        
        if (letters.length() < 7) {
            String extraLetters = fn + ln + fn + ln; 
            int idx = 0;
            while (letters.length() < 7 && idx < extraLetters.length()) {
                letters.append(extraLetters.charAt(idx++));
            }
            while (letters.length() < 7) {
                letters.append('a'); // extreme fallback for tiny names like "a"
            }
        }
        
        if (digits.length() < 3) {
            String extraDigits = year + month + day;
            int idx = 0;
            while (digits.length() < 3 && idx < extraDigits.length()) {
                digits.append(extraDigits.charAt(idx++));
            }
            int num = 1;
            while (digits.length() < 3) {
                digits.append(num++);
            }
        }
        
        if (letters.length() + digits.length() <= 10) {
            String extraCombined = ln + fn + year + month + day;
            int idx = 0;
            while (letters.length() + digits.length() <= 10 && idx < extraCombined.length()) {
                char c = extraCombined.charAt(idx++);
                if (Character.isLetter(c)) letters.append(c);
                else if (Character.isDigit(c)) digits.append(c);
            }
            int num = 1;
            while (letters.length() + digits.length() <= 10) {
                digits.append(num++);
            }
        }
        
        return letters.toString() + digits.toString();
    }

    private boolean isUsernameAvailable(String username) {
        return !userRepository.existsByUsername(username) && 
               !mailAccountRepository.existsByEmail(username + "@bnxmail.com");
    }

    /**
     * Create and persist a refresh token with metadata
     */
    public String createRefreshToken(User user, String ipAddress, String userAgent) {
        // Build the actual JWT for refresh
        String tokenStr = jwtUtil.generateRefreshToken(user.getEmail() != null ? user.getEmail() : user.getUsername());
        
        // Fetch location
        String location = null;
        if (ipAddress != null && !ipAddress.equals("127.0.0.1") && !ipAddress.equals("0:0:0:0:0:0:0:1") && !ipAddress.startsWith("192.168.")) {
            try {
                java.net.URL url = new java.net.URL("http://ip-api.com/line/" + ipAddress);
                java.net.HttpURLConnection con = (java.net.HttpURLConnection) url.openConnection();
                con.setRequestMethod("GET");
                con.setConnectTimeout(2000);
                con.setReadTimeout(2000);
                
                java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(con.getInputStream()));
                java.util.List<String> lines = new java.util.ArrayList<>();
                String line;
                while ((line = in.readLine()) != null) {
                    lines.add(line);
                }
                in.close();
                
                if (lines.size() >= 14 && "success".equals(lines.get(0))) {
                    location = lines.get(5) + ", " + lines.get(4) + ", " + lines.get(2);
                }
            } catch (Exception e) {
                log.warn("Failed to fetch location for IP: " + ipAddress);
            }
        }

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .token(tokenStr)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .location(location)
                .expiryDate(Instant.now().plusMillis(604800000)) // 7 days
                .build();

        refreshTokenRepository.save(refreshToken);
        return tokenStr;
    }

    /**
     * Get all active sessions for current user
     */
    public java.util.List<com.btctech.mailapp.dto.SessionResponse> getActiveSessions(User user, String ipAddress, String userAgent) {
        return refreshTokenRepository.findAllByUserAndRevokedFalse(user).stream()
                .filter(token -> !token.isExpired())
                .map(token -> com.btctech.mailapp.dto.SessionResponse.builder()
                        .id(token.getId())
                        .ipAddress(token.getIpAddress())
                        .userAgent(token.getUserAgent())
                        .location(token.getLocation())
                        .createdAt(token.getCreatedAt())
                        .expiresAt(token.getExpiryDate())
                        .isCurrentSession(
                            (ipAddress != null && ipAddress.equals(token.getIpAddress())) &&
                            (userAgent != null && userAgent.equals(token.getUserAgent()))
                        )
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * Remotely revoke a specific session
     */
    @Transactional
    public void revokeSession(Long sessionId, User user) {
        RefreshToken token = refreshTokenRepository.findById(sessionId)
                .orElseThrow(() -> new MailException("Session not found"));

        if (!token.getUser().getId().equals(user.getId())) {
            throw new MailException("Unauthorized to revoke this session");
        }

        token.setRevoked(true);
        refreshTokenRepository.save(token);
        log.info("✓ Session {} revoked for user {}", sessionId, user.getUsername());
    }

    /**
     * Rotate or refresh access token using refresh token
     */
    @Transactional
    public LoginResponseData refreshToken(TokenRefreshRequest request) {
        String requestRefreshToken = request.getRefreshToken();

        return refreshTokenRepository.findByToken(requestRefreshToken)
                .map(this::verifyExpiration)
                .map(RefreshToken::getUser)
                .map(user -> {
                    String accessToken = jwtUtil.generateTokenForUser(user, user.getEmail());
                    
                    // ✅ FIX: Migrate password session to new access token
                    try {
                        String password = sessionService.getPasswordByUserId(user.getId());
                        if (password != null) {
                            MailAccount primaryAccount = mailboxService.getPrimaryEmail(user.getId());
                            sessionService.createSession(user.getId(), primaryAccount.getId(), password, accessToken);
                            log.info("✓ Migrated session to new access token for user: {}", user.getUsername());
                        }
                    } catch (Exception e) {
                        log.warn("⚠ Could not migrate password session during token refresh: {}", e.getMessage());
                    }

                    return buildLoginResponse(user, false, accessToken, requestRefreshToken);
                })
                .orElseThrow(() -> new MailException("Refresh token is not in database!"));
    }

    private RefreshToken verifyExpiration(RefreshToken token) {
        if (Boolean.FALSE.equals(token.getUser().getActive())) {
            throw new com.btctech.mailapp.exception.MailSecurityException("Account suspended");
        }
        if (token.isExpired() || token.isRevoked()) {
            refreshTokenRepository.delete(token);
            throw new MailException("Refresh token was expired or revoked. Please log in again.");
        }
        return token;
    }

    @Transactional
    public void logout(String refreshTokenStr) {
        refreshTokenRepository.findByToken(refreshTokenStr)
                .ifPresent(token -> {
                    token.setRevoked(true);
                    refreshTokenRepository.save(token);
                });
    }

    /**
     * Build the complex SaaS login response
     */
    public LoginResponseData buildLoginResponse(User user, boolean autoUpgraded, String accessToken, String refreshToken) {
        List<MailAccount> userMailboxes = mailboxService.getUserEmails(user.getId());
        
        List<LoginResponseData.MailboxSummary> boxSummaries = userMailboxes.stream()
                .map(box -> LoginResponseData.MailboxSummary.builder()
                        .emailId(box.getId())
                        .email(box.getEmail())
                        .isPrimary(box.getIsPrimary())
                        .build())
                .collect(Collectors.toList());

        boolean onboardedVal = true;
        if (user.getAccountType() == com.btctech.mailapp.entity.AccountType.BUSINESS) {
            onboardedVal = businessProfileRepository.findByUserId(user.getId())
                    .map(com.btctech.mailapp.entity.BusinessProfile::getOnboarded)
                    .orElse(false);
        }

        return LoginResponseData.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .email(user.getEmail())
                .role(user.getRole())
                .accountType(user.getAccountType().name())
                .isPrimary(user.getIsPrimary())
                .appName("BNX Mail")
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .accessTokenExpiresIn(jwtUtil.getExpirationSeconds(accessToken))
                .refreshTokenExpiresIn(jwtUtil.getExpirationSeconds(refreshToken))
                .profilePicture(user.getProfilePicture())
                .profilePictureUrl(user.getProfilePicture() != null ? "/api/users/profile-picture/" + user.getUsername() : null)
                .mailboxes(boxSummaries)
                .isAutoUpgraded(autoUpgraded)
                .onboarded(onboardedVal)
                .loginAt(LocalDateTime.now())
                .build();
    }

    /**
     * Get masked recovery options for the user
     */
    public RecoveryOptionsResponse getRecoveryOptions(String identifier) {
        User user = userService.getUserByEmailOrUsername(identifier);

        return RecoveryOptionsResponse.builder()
                .recoveryEmail(maskEmail(user.getRecoveryEmail()))
                .phoneNumber(maskPhone(user.getPhoneNumber()))
                .build();
    }

    /**
     * Generate and send OTP to the selected recovery method
     */
    @Transactional(rollbackFor = Exception.class)
    public void sendRecoveryOtp(SendOtpRequest request) {
        User user = userService.getUserByEmailOrUsername(request.getIdentifier());

        if ("EMAIL".equalsIgnoreCase(request.getMethod()) && (user.getRecoveryEmail() == null || user.getRecoveryEmail().trim().isEmpty())) {
            throw new MailException("No recovery email configured for this account");
        } else if ("PHONE".equalsIgnoreCase(request.getMethod()) && (user.getPhoneNumber() == null || user.getPhoneNumber().trim().isEmpty())) {
            throw new MailException("No recovery phone configured for this account");
        }

        // Clean up old tokens
        passwordResetTokenRepository.deleteByUser(user);

        // Generate 6-digit OTP
        SecureRandom random = new SecureRandom();
        int otpValue = 100000 + random.nextInt(900000);
        String otp = String.valueOf(otpValue);

        // Save token
        PasswordResetToken resetToken = PasswordResetToken.builder()
                .user(user)
                .token(otp)
                .expiryDate(LocalDateTime.now().plusMinutes(15))
                .build();
        passwordResetTokenRepository.save(resetToken);

        if ("EMAIL".equalsIgnoreCase(request.getMethod())) {
            sendOtpEmail(user.getRecoveryEmail(), otp);
        } else if ("PHONE".equalsIgnoreCase(request.getMethod())) {
            sendOtpSms(user.getPhoneNumber(), otp);
        } else {
            throw new MailException("Invalid recovery method");
        }
    }

    private void sendOtpEmail(String toAddress, String otp) {
        if (toAddress == null || toAddress.trim().isEmpty()) {
            throw new MailException("No recovery email configured for this account");
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            String fromAddress = (mailFrom != null && !mailFrom.trim().isEmpty()) ? mailFrom.trim() : "beta@beta-softnet.com";
            message.setFrom(fromAddress);
            message.setTo(toAddress.trim());
            message.setSubject("Your BNX Mail Password Reset OTP");
            message.setText("Your OTP for password reset is: " + otp + "\nThis code will expire in 15 minutes.\n\nIf you did not request this code, please ignore this email or contact support.");
            
            javaMailSender.send(message);
            log.info("Sent recovery OTP email to masked recipient: {}", maskEmail(toAddress));
        } catch (org.springframework.mail.MailAuthenticationException mae) {
            log.error("SMTP Authentication failed: Check SMTP credentials. Details: {}", mae.getMessage());
            throw new MailException("SMTP authentication failed. Please verify mail credentials or contact administrator.");
        } catch (org.springframework.mail.MailSendException mse) {
            log.error("Failed to deliver OTP email via SMTP: {}", mse.getMessage());
            throw new MailException("Failed to send OTP email via SMTP. Please check mail server configuration or try again later.");
        } catch (org.springframework.mail.MailException me) {
            log.error("Failed to send OTP email via Spring Mail: {}", me.getMessage());
            throw new MailException("Failed to send OTP email via mail server. Please try again later.");
        } catch (Exception e) {
            log.error("Failed to send OTP email: {}", e.getMessage());
            throw new MailException("Failed to send OTP email: " + e.getMessage());
        }
    }

    /**
     * Normalize Indian mobile numbers:
     * - If 10 digits, prefix with country code '91'
     * - If 11 digits starting with '0', replace '0' with '91'
     * - If 12 digits starting with '91', keep as is (avoids duplicate country code)
     */
    private String normalizeMobileNumber(String mobileNumber) {
        if (mobileNumber == null) {
            return "";
        }
        String clean = mobileNumber.replaceAll("[^0-9]", "");
        if (clean.length() == 10) {
            return "91" + clean;
        }
        if (clean.length() == 11 && clean.startsWith("0")) {
            return "91" + clean.substring(1);
        }
        if (clean.length() == 12 && clean.startsWith("91")) {
            return clean;
        }
        return clean;
    }

    private void sendOtpSms(String mobileNumber, String otp) {
        if (mobileNumber == null || mobileNumber.trim().isEmpty()) {
            throw new MailException("No recovery phone configured for this account");
        }

        String cleanMobile = normalizeMobileNumber(mobileNumber);
        if (cleanMobile.length() < 10) {
            throw new MailException("Invalid phone number format. Please provide a valid 10-digit mobile number.");
        }

        if (smsForgotPasswordTemplateId == null || smsForgotPasswordTemplateId.trim().isEmpty()) {
            log.error("MSG91 Delivery blocked: 'sms.provider.forgotPasswordTemplateId' is not configured.");
            throw new MailException("SMS service is not configured. Please contact support or use Email recovery.");
        }

        if (smsAuthKey == null || smsAuthKey.trim().isEmpty()) {
            log.error("MSG91 Delivery blocked: 'sms.provider.authkey' is not configured.");
            throw new MailException("SMS service authentication is not configured. Please contact support or use Email recovery.");
        }

        try {
            RestTemplate restTemplate = new RestTemplate();
            String url = "https://control.msg91.com/api/v5/flow";

            HttpHeaders headers = new HttpHeaders();
            headers.set("authkey", smsAuthKey.trim());
            headers.set("Accept", "application/json");
            headers.set("Content-Type", "application/json");

            String senderField = (smsSenderId != null && !smsSenderId.trim().isEmpty())
                    ? "  \"sender\": \"" + smsSenderId.trim() + "\",\n"
                    : "";

            String requestBody = "{\n" +
                    "  \"template_id\": \"" + smsForgotPasswordTemplateId.trim() + "\",\n" +
                    "  \"short_url\": \"0\",\n" +
                    senderField +
                    "  \"recipients\": [\n" +
                    "    {\n" +
                    "      \"mobiles\": \"" + cleanMobile + "\",\n" +
                    "      \"num\": \"" + otp + "\",\n" +
                    "      \"NUM\": \"" + otp + "\",\n" +
                    "      \"VAR1\": \"" + otp + "\",\n" +
                    "      \"var1\": \"" + otp + "\",\n" +
                    "      \"OTP\": \"" + otp + "\",\n" +
                    "      \"otp\": \"" + otp + "\"\n" +
                    "    }\n" +
                    "  ]\n" +
                    "}";

            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            
            String responseBody = response.getBody();
            log.info("MSG91 API response status: {}", response.getStatusCode());
            
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            if (responseBody != null) {
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(responseBody);
                if (root.has("type") && "error".equalsIgnoreCase(root.get("type").asText())) {
                    String errorMessage = root.has("message") ? root.get("message").asText() : "Unknown MSG91 Error";
                    log.error("MSG91 rejected OTP SMS delivery: {}", errorMessage);
                    throw new MailException("Failed to send OTP SMS: " + errorMessage);
                }
            }
            log.info("✓ OTP SMS successfully dispatched via MSG91 to masked recipient: {}", maskPhone(mobileNumber));
        } catch (MailException me) {
            throw me;
        } catch (org.springframework.web.client.HttpStatusCodeException httpEx) {
            log.error("MSG91 HTTP error: status {}", httpEx.getStatusCode());
            String errorMsg = "SMS delivery failed";
            try {
                com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(httpEx.getResponseBodyAsString());
                if (root.has("message")) {
                    errorMsg = root.get("message").asText();
                }
            } catch (Exception ignored) {}
            throw new MailException("Failed to send OTP SMS: " + errorMsg);
        } catch (Exception e) {
            log.error("Failed to send OTP SMS: {}", e.getMessage());
            throw new MailException("Failed to send OTP SMS. Please try again later.");
        }
    }

    /**
     * Validate OTP and reset password
     */
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        User user = userService.getUserByEmailOrUsername(request.getIdentifier());

        PasswordResetToken token = passwordResetTokenRepository.findByUserAndToken(user, request.getOtp())
                .orElseThrow(() -> new MailException("Invalid OTP"));

        if (token.isExpired()) {
            passwordResetTokenRepository.delete(token);
            throw new MailException("OTP has expired");
        }

        // Perform password update atomic flow
        userService.updateUserPassword(user, request.getNewPassword());

        // Delete used token
        passwordResetTokenRepository.delete(token);

        // Revoke all active refresh tokens so they have to log in with new password
        List<RefreshToken> activeTokens = refreshTokenRepository.findAllByUserAndRevokedFalse(user);
        for (RefreshToken rt : activeTokens) {
            rt.setRevoked(true);
            refreshTokenRepository.save(rt);
        }

        // Clean up active UserSession records so stale passwords don't trigger IMAP 500 errors
        sessionService.deleteSessionsByUserId(user.getId());

        log.info("Revoked all active sessions for user {} after password reset", user.getUsername());
    }

    /**
     * Standalone verification of OTP without consuming it,
     * so that the subsequent resetPassword step can complete.
     */
    public void verifyOtp(String identifier, String otp) {
        User user = userService.getUserByEmailOrUsername(identifier);

        PasswordResetToken token = passwordResetTokenRepository.findByUserAndToken(user, otp)
                .orElseThrow(() -> new MailException("Invalid OTP"));

        if (token.isExpired()) {
            passwordResetTokenRepository.delete(token);
            throw new MailException("OTP has expired");
        }
        
        log.info("✓ OTP verified successfully for: {}", identifier);
    }

    /**
     * Consume and delete OTP token (used for 2FA bypass recovery)
     */
    public void consumeOtp(String identifier, String otp) {
        User user = userService.getUserByEmailOrUsername(identifier);
        passwordResetTokenRepository.findByUserAndToken(user, otp)
                .ifPresent(passwordResetTokenRepository::delete);
    }

    private final com.btctech.mailapp.repository.ExternalAppSessionRepository externalAppSessionRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Get all active external application sessions for current user
     */
    public List<com.btctech.mailapp.dto.ExternalSessionResponse> getExternalSessions(User user) {
        List<com.btctech.mailapp.entity.ExternalAppSession> all = externalAppSessionRepository.findByUserOrderByLoggedInAtDesc(user);
        java.util.Map<String, com.btctech.mailapp.entity.ExternalAppSession> latestByClient = new java.util.LinkedHashMap<>();
        for (com.btctech.mailapp.entity.ExternalAppSession s : all) {
            String cId = (s.getClientApp() != null && s.getClientApp().getClientId() != null)
                    ? s.getClientApp().getClientId()
                    : ("app_" + s.getId());
            latestByClient.putIfAbsent(cId, s);
        }

        return latestByClient.values().stream()
                .map(session -> {
                    boolean isRevoked = Boolean.TRUE.equals(session.getRevoked());
                    return com.btctech.mailapp.dto.ExternalSessionResponse.builder()
                            .id(session.getId())
                            .appName(session.getClientApp() != null ? session.getClientApp().getAppName() : "Unknown App")
                            .clientId(session.getClientApp() != null ? session.getClientApp().getClientId() : null)
                            .loggedInAt(session.getLoggedInAt())
                            .ipAddress(session.getIpAddress())
                            .userAgent(session.getUserAgent())
                            .location(session.getLocation())
                            .latitude(session.getLatitude())
                            .longitude(session.getLongitude())
                            .revoked(isRevoked)
                            .revokedAt(session.getRevokedAt())
                            .status(isRevoked ? "Revoked" : "Authorized")
                            .build();
                })
                .collect(Collectors.toList());
    }

    // Helper functions for masking
    @Transactional
    public void revokeExternalSession(Long sessionId, User user) {
        com.btctech.mailapp.entity.ExternalAppSession session = externalAppSessionRepository.findById(sessionId)
                .orElseThrow(() -> new MailException("External session not found"));

        if (!session.getUser().getId().equals(user.getId())) {
            throw new MailException("Unauthorized to revoke this external session");
        }

        session.setRevoked(true);
        session.setRevokedAt(LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata")));
        externalAppSessionRepository.save(session);

        // Invalidate other active sessions for this client for this user to guarantee full revocation
        if (session.getClientApp() != null && session.getClientApp().getClientId() != null) {
            List<com.btctech.mailapp.entity.ExternalAppSession> activeOthers =
                    externalAppSessionRepository.findActiveByUserAndClientId(user, session.getClientApp().getClientId());
            for (com.btctech.mailapp.entity.ExternalAppSession other : activeOthers) {
                if (!other.getId().equals(session.getId())) {
                    other.setRevoked(true);
                    other.setRevokedAt(LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata")));
                    externalAppSessionRepository.save(other);
                }
            }
        }

        log.info("✓ External session {} ({}) marked revoked for user {}", sessionId, session.getClientApp().getAppName(), user.getUsername());

        broadcastSessionRevocation(session, user);
    }

    public void broadcastSessionRevocation(com.btctech.mailapp.entity.ExternalAppSession session, User user) {
        if (messagingTemplate == null) return;
        try {
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("type", "SESSION_REVOKED");
            payload.put("action", "REVOKE_SESSION");
            payload.put("sessionId", session.getId());
            payload.put("clientId", session.getClientApp().getClientId());
            payload.put("appName", session.getClientApp().getAppName());
            payload.put("userId", user.getId());
            payload.put("email", user.getEmail());
            payload.put("username", user.getUsername());
            payload.put("timestamp", System.currentTimeMillis());
            payload.put("message", "Your session has been revoked. Please log in again.");

            messagingTemplate.convertAndSend("/topic/session-revocation", payload);
            messagingTemplate.convertAndSend("/topic/oauth-revocation", payload);
            messagingTemplate.convertAndSend("/topic/user/" + user.getId() + "/session-revocation", payload);
            messagingTemplate.convertAndSend("/topic/client/" + session.getClientApp().getClientId() + "/session-revocation", payload);
            log.info("✓ Broadcasted revocation event for session {} ({}) over WebSocket", session.getId(), session.getClientApp().getClientId());
        } catch (Exception e) {
            log.warn("Could not broadcast session revocation over WebSocket: {}", e.getMessage());
        }
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) return null;
        String[] parts = email.split("@");
        String name = parts[0];
        String domain = parts[1];
        if (name.length() <= 2) {
            return name + "***@" + domain;
        }
        return name.charAt(0) + "***" + name.charAt(name.length() - 1) + "@" + domain;
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return null;
        return "*******" + phone.substring(phone.length() - 3);
    }
}
