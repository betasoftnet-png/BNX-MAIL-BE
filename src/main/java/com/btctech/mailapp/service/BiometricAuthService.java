package com.btctech.mailapp.service;

import com.btctech.mailapp.config.JwtUtil;
import com.btctech.mailapp.dto.LoginResponseData;
import com.btctech.mailapp.entity.BiometricCredential;
import com.btctech.mailapp.entity.MailAccount;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.entity.UserSettings;
import com.btctech.mailapp.repository.BiometricCredentialRepository;
import com.btctech.mailapp.repository.UserRepository;
import com.btctech.mailapp.repository.UserSettingsRepository;
import com.btctech.mailapp.util.WebAuthnCbor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class BiometricAuthService {

    private final BiometricCredentialRepository biometricCredentialRepository;
    private final UserRepository userRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final AuthService authService;
    private final JwtUtil jwtUtil;
    private final MailboxService mailboxService;
    private final SessionService sessionService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${webauthn.rp.id:localhost}")
    private String configuredRpId;

    @Value("${webauthn.rp.name:BNXmail}")
    private String rpName;

    private static class ChallengeData {
        final String challenge;
        final long expiresAt;

        ChallengeData(String challenge) {
            this.challenge = challenge;
            this.expiresAt = System.currentTimeMillis() + 120_000L; // 2 minutes TTL
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    private final Map<String, ChallengeData> registrationChallenges = new ConcurrentHashMap<>();
    private final Map<String, ChallengeData> authChallenges = new ConcurrentHashMap<>();

    private String generateRandomChallenge() {
        byte[] challengeBytes = new byte[32];
        secureRandom.nextBytes(challengeBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(challengeBytes);
    }

    private String resolveRpId(String origin) {
        if (origin != null && !origin.trim().isEmpty()) {
            try {
                URI uri = URI.create(origin);
                String host = uri.getHost();
                if (host != null) {
                    if (host.equals("localhost") || host.equals("127.0.0.1")) {
                        return "localhost";
                    }
                    return host;
                }
            } catch (Exception ignored) {}
        }
        return (configuredRpId != null && !configuredRpId.trim().isEmpty()) ? configuredRpId : "localhost";
    }

    /**
     * Step 1: Generate registration options for an authenticated user
     */
    public Map<String, Object> generateRegistrationOptions(User user, String origin) {
        String challenge = generateRandomChallenge();
        registrationChallenges.put("user_" + user.getId(), new ChallengeData(challenge));

        String rpId = resolveRpId(origin);

        Map<String, Object> rp = new HashMap<>();
        rp.put("name", rpName);
        rp.put("id", rpId);

        String userHandle = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(user.getId().toString().getBytes(StandardCharsets.UTF_8));
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("id", userHandle);
        userInfo.put("name", (user.getEmail() != null) ? user.getEmail() : user.getUsername());
        userInfo.put("displayName", (user.getDisplayName() != null && !user.getDisplayName().trim().isEmpty())
                ? user.getDisplayName() : user.getUsername());

        List<Map<String, Object>> pubKeyCredParams = new ArrayList<>();
        pubKeyCredParams.add(Map.of("type", "public-key", "alg", -7));  // ES256
        pubKeyCredParams.add(Map.of("type", "public-key", "alg", -257)); // RS256

        Map<String, Object> authenticatorSelection = new HashMap<>();
        authenticatorSelection.put("authenticatorAttachment", "platform");
        authenticatorSelection.put("userVerification", "required");
        authenticatorSelection.put("residentKey", "discouraged");
        authenticatorSelection.put("requireResidentKey", false);

        // Exclude already registered credentials for this user
        List<BiometricCredential> existing = biometricCredentialRepository.findByUserAndRevokedFalse(user);
        List<Map<String, Object>> excludeCredentials = new ArrayList<>();
        for (BiometricCredential cred : existing) {
            Map<String, Object> item = new HashMap<>();
            item.put("type", "public-key");
            item.put("id", cred.getCredentialId());
            item.put("transports", List.of("internal"));
            excludeCredentials.add(item);
        }

        Map<String, Object> options = new HashMap<>();
        options.put("challenge", challenge);
        options.put("rp", rp);
        options.put("user", userInfo);
        options.put("pubKeyCredParams", pubKeyCredParams);
        options.put("authenticatorSelection", authenticatorSelection);
        options.put("timeout", 60000);
        options.put("attestation", "none");
        options.put("excludeCredentials", excludeCredentials);

        log.info("Generated WebAuthn registration options for user: {}", user.getUsername());
        return options;
    }

    /**
     * Step 2: Verify registration response and store biometric credential
     */
    @Transactional
    public BiometricCredential verifyAndRegister(User user, Map<String, Object> request, String origin) {
        String key = "user_" + user.getId();
        ChallengeData challengeData = registrationChallenges.remove(key);
        if (challengeData == null || challengeData.isExpired()) {
            throw new IllegalArgumentException("Biometric registration challenge expired or not found. Please try again.");
        }

        String credentialId = (String) request.get("id");
        if (credentialId == null || credentialId.trim().isEmpty()) {
            credentialId = (String) request.get("rawId");
        }
        if (credentialId == null || credentialId.trim().isEmpty()) {
            throw new IllegalArgumentException("Credential ID is required");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) request.get("response");
        if (response == null) {
            throw new IllegalArgumentException("WebAuthn response object is required");
        }

        String clientDataJSONBase64 = (String) response.get("clientDataJSON");
        String attestationObjectBase64 = (String) response.get("attestationObject");

        if (clientDataJSONBase64 == null || attestationObjectBase64 == null) {
            throw new IllegalArgumentException("clientDataJSON and attestationObject are required");
        }

        // 1. Verify clientDataJSON
        byte[] clientDataJSONBytes = Base64.getUrlDecoder().decode(clientDataJSONBase64);
        String clientDataJSONStr = new String(clientDataJSONBytes, StandardCharsets.UTF_8);

        try {
            JsonNode clientData = objectMapper.readTree(clientDataJSONStr);
            String type = clientData.path("type").asText();
            if (!"webauthn.create".equals(type)) {
                throw new IllegalArgumentException("Invalid clientData type: " + type);
            }

            String returnedChallenge = clientData.path("challenge").asText();
            if (!challengeData.challenge.equals(returnedChallenge)) {
                throw new IllegalArgumentException("WebAuthn challenge mismatch");
            }
        } catch (IllegalArgumentException iae) {
            throw iae;
        } catch (Exception e) {
            throw new RuntimeException("Could not parse WebAuthn clientDataJSON", e);
        }

        // 2. Parse attestationObject (CBOR)
        byte[] attestationBytes = Base64.getUrlDecoder().decode(attestationObjectBase64);
        WebAuthnCbor.CborDecoder cborDecoder = new WebAuthnCbor.CborDecoder(attestationBytes);
        Object decoded = cborDecoder.decode();
        if (!(decoded instanceof Map<?, ?> attestationMap)) {
            throw new IllegalArgumentException("Invalid attestationObject CBOR format");
        }

        byte[] authDataBytes = (byte[]) attestationMap.get("authData");
        if (authDataBytes == null) {
            throw new IllegalArgumentException("Missing authData in attestationObject");
        }

        WebAuthnCbor.ParsedAuthData parsedAuthData = WebAuthnCbor.parseAuthData(authDataBytes);
        if (!parsedAuthData.isUserPresent()) {
            throw new IllegalArgumentException("User Presence (UP) flag not set in authenticator data");
        }

        PublicKey publicKey = parsedAuthData.getPublicKey();
        if (publicKey == null) {
            throw new IllegalArgumentException("Could not extract public key from authenticator attestation");
        }

        String publicKeyX509Base64 = WebAuthnCbor.encodePublicKeyToX509Base64(publicKey);

        // Normalize credential ID
        String normalizedCredId = credentialId.trim();

        // Optional friendly device name
        String deviceName = (String) request.get("deviceName");
        if (deviceName == null || deviceName.trim().isEmpty()) {
            deviceName = "Platform Authenticator (Windows Hello / Touch ID)";
        }

        // Deactivate any previous matching credential ID
        Optional<BiometricCredential> existing = biometricCredentialRepository.findByCredentialId(normalizedCredId);
        BiometricCredential credential;
        if (existing.isPresent()) {
            credential = existing.get();
            credential.setUser(user);
            credential.setPublicKeyCose(publicKeyX509Base64);
            credential.setAlgorithm(parsedAuthData.getAlgorithm());
            credential.setSignCount(parsedAuthData.getSignCount());
            credential.setDeviceName(deviceName);
            credential.setRevoked(false);
            credential.setLastUsedAt(LocalDateTime.now());
        } else {
            credential = BiometricCredential.builder()
                    .user(user)
                    .credentialId(normalizedCredId)
                    .publicKeyCose(publicKeyX509Base64)
                    .algorithm(parsedAuthData.getAlgorithm())
                    .signCount(parsedAuthData.getSignCount())
                    .deviceName(deviceName)
                    .transports("internal")
                    .revoked(false)
                    .build();
        }

        biometricCredentialRepository.save(credential);

        // Enable biometrics authoritatively on User and UserSettings
        user.setBiometricsEnabled(true);
        userRepository.save(user);

        UserSettings settings = userSettingsRepository.findByUser(user)
                .orElseGet(() -> UserSettings.builder().user(user).build());
        settings.setBiometricsEnabled(true);
        userSettingsRepository.save(settings);

        log.info("✓ Biometric credential successfully registered for user: {} (credentialId: {}...)",
                user.getUsername(), normalizedCredId.substring(0, Math.min(16, normalizedCredId.length())));
        return credential;
    }

    /**
     * Disable biometric authentication for user and revoke credentials
     */
    @Transactional
    public void disableBiometrics(User user) {
        List<BiometricCredential> activeCreds = biometricCredentialRepository.findByUserAndRevokedFalse(user);
        for (BiometricCredential c : activeCreds) {
            c.setRevoked(true);
        }
        biometricCredentialRepository.saveAll(activeCreds);

        user.setBiometricsEnabled(false);
        userRepository.save(user);

        UserSettings settings = userSettingsRepository.findByUser(user).orElse(null);
        if (settings != null) {
            settings.setBiometricsEnabled(false);
            userSettingsRepository.save(settings);
        }

        log.info("✓ Biometric authentication disabled and credentials revoked for user: {}", user.getUsername());
    }

    /**
     * Get biometric status for authenticated user
     */
    public Map<String, Object> getBiometricStatus(User user) {
        boolean enabled = Boolean.TRUE.equals(user.getBiometricsEnabled());
        List<BiometricCredential> credentials = biometricCredentialRepository.findByUserAndRevokedFalse(user);

        List<Map<String, Object>> credList = new ArrayList<>();
        for (BiometricCredential c : credentials) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", c.getId());
            item.put("deviceName", c.getDeviceName());
            item.put("createdAt", c.getCreatedAt());
            item.put("lastUsedAt", c.getLastUsedAt());
            item.put("credentialIdMasked", c.getCredentialId().substring(0, Math.min(8, c.getCredentialId().length())) + "...");
            credList.add(item);
        }

        Map<String, Object> status = new HashMap<>();
        status.put("biometricsEnabled", enabled && !credentials.isEmpty());
        status.put("credentialCount", credentials.size());
        status.put("credentials", credList);
        return status;
    }

    /**
     * Step 1: Generate login options for biometric login
     */
    public Map<String, Object> generateLoginOptions(String email, String origin) {
        if (email == null || email.trim().isEmpty()) {
            throw new IllegalArgumentException("Email address is required for biometric sign-in");
        }

        String rawEmail = email.trim().toLowerCase();
        final String cleanEmail = rawEmail.contains("@") ? rawEmail : rawEmail + "@bnxmail.com";

        User user = userRepository.findByEmail(cleanEmail)
                .or(() -> userRepository.findByUsername(cleanEmail))
                .orElseThrow(() -> new IllegalArgumentException("No account found with this email address"));

        if (!Boolean.TRUE.equals(user.getBiometricsEnabled())) {
            throw new IllegalStateException("Biometric authentication is not enabled for this account. Please sign in with your password.");
        }

        List<BiometricCredential> credentials = biometricCredentialRepository.findByUserAndRevokedFalse(user);
        if (credentials.isEmpty()) {
            throw new IllegalStateException("No registered biometric devices found for this account. Please sign in with password.");
        }

        String challenge = generateRandomChallenge();
        authChallenges.put("login_" + user.getId(), new ChallengeData(challenge));

        String rpId = resolveRpId(origin);

        List<Map<String, Object>> allowCredentials = new ArrayList<>();
        for (BiometricCredential cred : credentials) {
            Map<String, Object> item = new HashMap<>();
            item.put("type", "public-key");
            item.put("id", cred.getCredentialId());
            item.put("transports", List.of("internal"));
            allowCredentials.add(item);
        }

        Map<String, Object> options = new HashMap<>();
        options.put("challenge", challenge);
        options.put("rpId", rpId);
        options.put("timeout", 60000);
        options.put("userVerification", "required");
        options.put("allowCredentials", allowCredentials);

        log.info("Generated WebAuthn login options for user: {}", user.getUsername());
        return options;
    }

    /**
     * Step 2: Verify login signature and create session
     */
    @Transactional
    public LoginResponseData verifyLogin(Map<String, Object> request, String origin, String ipAddress, String userAgent) {
        String email = (String) request.get("email");
        String tempToken = (String) request.get("tempToken");
        if ((email == null || email.trim().isEmpty()) && tempToken != null) {
            try {
                String sub = jwtUtil.extractEmail(tempToken);
                email = sub.replace("bio_", "").replace("2fa_", "");
            } catch (Exception e) {
                log.warn("Could not extract email from tempToken: {}", e.getMessage());
            }
        }

        if (email == null || email.trim().isEmpty()) {
            throw new IllegalArgumentException("Email address is required");
        }

        String rawPassword = null;
        if (tempToken != null && !tempToken.trim().isEmpty()) {
            try {
                io.jsonwebtoken.Claims claims = jwtUtil.extractAllClaims(tempToken);
                String encryptedPassword = claims.get("ep", String.class);
                if (encryptedPassword != null) {
                    rawPassword = sessionService.decrypt(encryptedPassword);
                }
            } catch (Exception e) {
                log.warn("Could not decrypt password from tempToken in biometric verify: {}", e.getMessage());
            }
        }

        String rawEmail = email.trim().toLowerCase();
        final String cleanEmail = rawEmail.contains("@") ? rawEmail : rawEmail + "@bnxmail.com";

        User user = userRepository.findByEmail(cleanEmail)
                .or(() -> userRepository.findByUsername(cleanEmail))
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (!Boolean.TRUE.equals(user.getBiometricsEnabled())) {
            throw new IllegalStateException("Biometric authentication is not enabled for this account");
        }

        String challengeKey = "login_" + user.getId();
        ChallengeData challengeData = authChallenges.remove(challengeKey);
        if (challengeData == null || challengeData.isExpired()) {
            throw new IllegalArgumentException("Authentication challenge expired or invalid. Please try again.");
        }

        String credentialId = (String) request.get("id");
        if (credentialId == null || credentialId.trim().isEmpty()) {
            credentialId = (String) request.get("rawId");
        }
        if (credentialId == null) {
            throw new IllegalArgumentException("Credential ID is required");
        }

        BiometricCredential credential = biometricCredentialRepository.findByCredentialIdAndRevokedFalse(credentialId.trim())
                .orElseThrow(() -> new IllegalArgumentException("Unknown or revoked biometric credential"));

        if (!credential.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Biometric credential does not belong to this account");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) request.get("response");
        if (response == null) {
            throw new IllegalArgumentException("WebAuthn authentication response is required");
        }

        String clientDataJSONBase64 = (String) response.get("clientDataJSON");
        String authenticatorDataBase64 = (String) response.get("authenticatorData");
        String signatureBase64 = (String) response.get("signature");

        if (clientDataJSONBase64 == null || authenticatorDataBase64 == null || signatureBase64 == null) {
            throw new IllegalArgumentException("clientDataJSON, authenticatorData, and signature are required");
        }

        // 1. Validate clientDataJSON
        byte[] clientDataJSONBytes = Base64.getUrlDecoder().decode(clientDataJSONBase64);
        String clientDataJSONStr = new String(clientDataJSONBytes, StandardCharsets.UTF_8);

        try {
            JsonNode clientData = objectMapper.readTree(clientDataJSONStr);
            String type = clientData.path("type").asText();
            if (!"webauthn.get".equals(type)) {
                throw new IllegalArgumentException("Invalid clientData type: " + type);
            }

            String returnedChallenge = clientData.path("challenge").asText();
            if (!challengeData.challenge.equals(returnedChallenge)) {
                throw new IllegalArgumentException("Authentication challenge mismatch");
            }
        } catch (IllegalArgumentException iae) {
            throw iae;
        } catch (Exception e) {
            throw new RuntimeException("Could not parse WebAuthn clientDataJSON", e);
        }

        // 2. Validate authenticatorData
        byte[] authDataBytes = Base64.getUrlDecoder().decode(authenticatorDataBase64);
        WebAuthnCbor.ParsedAuthData parsedAuthData = WebAuthnCbor.parseAuthData(authDataBytes);
        if (!parsedAuthData.isUserPresent()) {
            throw new IllegalArgumentException("User Presence (UP) flag not set");
        }

        // Sign count check to detect cloned authenticators
        if (parsedAuthData.getSignCount() > 0 && parsedAuthData.getSignCount() <= credential.getSignCount()) {
            log.warn("Potential authenticator cloning detected for user {}: current signCount {} <= stored {}",
                    user.getUsername(), parsedAuthData.getSignCount(), credential.getSignCount());
        }
        credential.setSignCount(parsedAuthData.getSignCount());

        // 3. Verify digital signature against stored public key
        byte[] signatureBytes = Base64.getUrlDecoder().decode(signatureBase64);
        PublicKey publicKey = WebAuthnCbor.decodePublicKeyFromX509Base64(credential.getPublicKeyCose(), credential.getAlgorithm());

        boolean signatureValid = WebAuthnCbor.verifySignature(publicKey, credential.getAlgorithm(), authDataBytes, clientDataJSONBytes, signatureBytes);
        if (!signatureValid) {
            log.warn("✗ WebAuthn biometric signature verification failed for user: {}", user.getUsername());
            throw new IllegalArgumentException("Biometric verification failed. Please check your credentials or try again.");
        }

        // 4. Update credential lastUsedAt & user lastLogin
        credential.setLastUsedAt(LocalDateTime.now());
        biometricCredentialRepository.save(credential);

        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);

        // 5. Issue Tokens & Create Session
        String userEmail = user.getEmail() != null ? user.getEmail() : cleanEmail;
        String accessToken = jwtUtil.generateTokenForUser(user, userEmail);
        String refreshToken = authService.createRefreshToken(user, ipAddress, userAgent);

        // Mail session creation using decrypted password from tempToken or MailAccount
        try {
            MailAccount mailAccount = mailboxService.getMailAccountByEmail(userEmail);
            if (mailAccount != null) {
                if (rawPassword != null && !rawPassword.isEmpty()) {
                    sessionService.createSession(user.getId(), mailAccount.getId(), rawPassword, accessToken);
                    if (mailAccount.getEncryptedPassword() == null) {
                        mailAccount.setEncryptedPassword(sessionService.encrypt(rawPassword));
                        mailboxService.saveMailAccount(mailAccount);
                    }
                } else if (mailAccount.getEncryptedPassword() != null && !mailAccount.getEncryptedPassword().isEmpty()) {
                    String decryptedPassword = sessionService.decrypt(mailAccount.getEncryptedPassword());
                    sessionService.createSession(user.getId(), mailAccount.getId(), decryptedPassword, accessToken);
                }
            }
        } catch (Exception e) {
            log.warn("Could not create IMAP mail session during biometric login: {}", e.getMessage());
        }

        LoginResponseData loginData = authService.buildLoginResponse(user, false, accessToken, refreshToken);
        log.info("✓ User {} logged in successfully via Biometric Authentication ({})", user.getUsername(), credential.getDeviceName());
        return loginData;
    }
}
