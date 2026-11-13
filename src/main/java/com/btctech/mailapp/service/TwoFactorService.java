package com.btctech.mailapp.service;

import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.entity.UserRecoveryCode;
import com.btctech.mailapp.repository.UserRecoveryCodeRepository;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static dev.samstevens.totp.util.Utils.getDataUriForImage;

@Slf4j
@Service
public class TwoFactorService {

    private static final String ENCRYPTION_PREFIX = "enc:";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final String RECOVERY_CODE_CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    @Value("${encryption.key:1234567890123456}")
    private String encryptionKey;

    @Autowired
    private UserRecoveryCodeRepository userRecoveryCodeRepository;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, AttemptCounter> attemptTracker = new ConcurrentHashMap<>();

    private static class AttemptCounter {
        int count;
        long resetTime;

        AttemptCounter(int count, long resetTime) {
            this.count = count;
            this.resetTime = resetTime;
        }
    }

    public String generateSecret() {
        SecretGenerator generator = new DefaultSecretGenerator();
        return generator.generate();
    }

    /**
     * Encrypt secret at rest using AES-GCM (Authenticated Encryption)
     */
    public String encryptSecret(String plainSecret) {
        if (plainSecret == null || plainSecret.trim().isEmpty()) {
            return null;
        }
        if (plainSecret.startsWith(ENCRYPTION_PREFIX)) {
            return plainSecret; // Already encrypted
        }
        try {
            byte[] keyBytes = MessageDigest.getInstance("SHA-256")
                    .digest(encryptionKey.getBytes(StandardCharsets.UTF_8));
            SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");

            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] cipherText = cipher.doFinal(plainSecret.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);

            return ENCRYPTION_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("Failed to encrypt 2FA secret: {}", e.getMessage());
            throw new RuntimeException("Could not securely encrypt 2FA secret");
        }
    }

    /**
     * Decrypt secret at rest with backward compatibility
     */
    public String decryptSecret(String storedSecret) {
        if (storedSecret == null || storedSecret.trim().isEmpty()) {
            return null;
        }
        if (!storedSecret.startsWith(ENCRYPTION_PREFIX)) {
            return storedSecret; // Legacy plaintext secret
        }
        try {
            byte[] keyBytes = MessageDigest.getInstance("SHA-256")
                    .digest(encryptionKey.getBytes(StandardCharsets.UTF_8));
            SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");

            String base64Data = storedSecret.substring(ENCRYPTION_PREFIX.length());
            byte[] combined = Base64.getDecoder().decode(base64Data);

            if (combined.length < GCM_IV_LENGTH) {
                throw new IllegalArgumentException("Invalid encrypted 2FA payload");
            }

            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);

            int cipherLength = combined.length - GCM_IV_LENGTH;
            byte[] cipherText = new byte[cipherLength];
            System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherLength);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] plainBytes = cipher.doFinal(cipherText);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to decrypt 2FA secret: {}", e.getMessage());
            throw new RuntimeException("Could not decrypt 2FA secret");
        }
    }

    /**
     * Generates a base64 PNG data URI QR code image that can be rendered directly in an <img src=... /> tag
     */
    public String getQrCodeUrl(String secret, String email) {
        try {
            QrData data = new QrData.Builder()
                    .label(email)
                    .secret(secret)
                    .issuer("BNX Auth")
                    .algorithm(HashingAlgorithm.SHA1)
                    .digits(6)
                    .period(30)
                    .build();

            QrGenerator generator = new ZxingPngQrGenerator();
            byte[] imageData = generator.generate(data);
            return getDataUriForImage(imageData, generator.getImageMimeType());
        } catch (Throwable t) {
            log.info("Returning otpauth URI for 2FA QR code: {}", t.getMessage());
            return getOtpauthUri(secret, email);
        }
    }

    /**
     * Generates standard otpauth:// URI
     */
    public String getOtpauthUri(String secret, String email) {
        QrData data = new QrData.Builder()
                .label(email)
                .secret(secret)
                .issuer("BNX Auth")
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(30)
                .build();
        return data.getUri();
    }

    /**
     * Rate-limits verification attempts (max 5 failed attempts per 60s per user)
     */
    public boolean checkRateLimit(String rateLimitKey) {
        long now = System.currentTimeMillis();
        AttemptCounter counter = attemptTracker.get(rateLimitKey);
        if (counter == null || now > counter.resetTime) {
            return true;
        }
        return counter.count < 5;
    }

    public void recordFailedAttempt(String rateLimitKey) {
        long now = System.currentTimeMillis();
        attemptTracker.compute(rateLimitKey, (k, v) -> {
            if (v == null || now > v.resetTime) {
                return new AttemptCounter(1, now + 60000);
            }
            v.count++;
            return v;
        });
    }

    public void resetFailedAttempts(String rateLimitKey) {
        attemptTracker.remove(rateLimitKey);
    }

    /**
     * Verifies TOTP with replay protection and per-user time step tracking
     */
    public boolean verifyCode(User user, String code) {
        if (user == null || user.getTwoFactorSecret() == null || code == null || code.trim().length() != 6) {
            return false;
        }

        String rateLimitKey = "user_" + user.getId();
        if (!checkRateLimit(rateLimitKey)) {
            log.warn("Rate limit exceeded for 2FA verification on user: {}", user.getUsername());
            throw new RuntimeException("Too many invalid attempts. Please wait 1 minute before trying again.");
        }

        String plainSecret = decryptSecret(user.getTwoFactorSecret());
        if (plainSecret == null) {
            return false;
        }

        long currentSeconds = System.currentTimeMillis() / 1000L;
        long currentBucket = currentSeconds / 30L;

        CodeGenerator codeGenerator = new DefaultCodeGenerator();
        Long matchedBucket = null;

        for (long b = currentBucket - 1; b <= currentBucket + 1; b++) {
            try {
                String expected = codeGenerator.generate(plainSecret, b);
                if (expected.equals(code.trim())) {
                    matchedBucket = b;
                    break;
                }
            } catch (Exception ignored) {}
        }

        if (matchedBucket == null) {
            recordFailedAttempt(rateLimitKey);
            return false;
        }

        // Replay protection: The matched bucket must be strictly greater than user's last used bucket
        if (user.getLastTotpStep() != null && matchedBucket <= user.getLastTotpStep()) {
            log.warn("TOTP replay attempt rejected for user: {} (attempted step: {}, lastStep: {})",
                    user.getUsername(), matchedBucket, user.getLastTotpStep());
            return false;
        }

        user.setLastTotpStep(matchedBucket);
        resetFailedAttempts(rateLimitKey);
        return true;
    }

    /**
     * Backward-compatible simple verification for initial setup before user entity has lastTotpStep
     */
    public boolean verifyCode(String secret, String code) {
        if (secret == null || code == null || code.trim().length() != 6) {
            return false;
        }
        String plainSecret = decryptSecret(secret);
        if (plainSecret == null) {
            return false;
        }

        long currentSeconds = System.currentTimeMillis() / 1000L;
        long currentBucket = currentSeconds / 30L;

        CodeGenerator codeGenerator = new DefaultCodeGenerator();
        for (long b = currentBucket - 1; b <= currentBucket + 1; b++) {
            try {
                String expected = codeGenerator.generate(plainSecret, b);
                if (expected.equals(code.trim())) {
                    return true;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    /**
     * Generate 10 single-use recovery codes, store SHA-256 hashes, and return formatted plaintext codes
     */
    @Transactional
    public List<String> generateRecoveryCodes(User user, int count) {
        userRecoveryCodeRepository.deleteByUser(user);

        List<String> plainCodes = new ArrayList<>();
        List<UserRecoveryCode> entities = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            StringBuilder rawCode = new StringBuilder();
            for (int j = 0; j < 8; j++) {
                rawCode.append(RECOVERY_CODE_CHARS.charAt(secureRandom.nextInt(RECOVERY_CODE_CHARS.length())));
            }
            // Formatted as XXXX-XXXX
            String formatted = rawCode.substring(0, 4) + "-" + rawCode.substring(4);
            plainCodes.add(formatted);

            String hash = hashRecoveryCode(rawCode.toString());
            UserRecoveryCode rec = new UserRecoveryCode();
            rec.setUser(user);
            rec.setCodeHash(hash);
            rec.setUsed(false);
            entities.add(rec);
        }

        userRecoveryCodeRepository.saveAll(entities);
        log.info("Generated {} new recovery codes for user: {}", count, user.getUsername());
        return plainCodes;
    }

    /**
     * Verify and immediately consume a single-use recovery code
     */
    @Transactional
    public boolean verifyAndConsumeRecoveryCode(User user, String inputCode) {
        if (user == null || inputCode == null || inputCode.trim().isEmpty()) {
            return false;
        }

        String rateLimitKey = "rec_" + user.getId();
        if (!checkRateLimit(rateLimitKey)) {
            log.warn("Rate limit exceeded for recovery code on user: {}", user.getUsername());
            throw new RuntimeException("Too many invalid attempts. Please wait 1 minute before trying again.");
        }

        String normalized = inputCode.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        if (normalized.length() != 8) {
            recordFailedAttempt(rateLimitKey);
            return false;
        }

        String hash = hashRecoveryCode(normalized);
        List<UserRecoveryCode> activeCodes = userRecoveryCodeRepository.findByUserAndUsedFalse(user);

        for (UserRecoveryCode rec : activeCodes) {
            if (rec.getCodeHash().equals(hash)) {
                rec.setUsed(true);
                rec.setUsedAt(LocalDateTime.now());
                userRecoveryCodeRepository.save(rec);
                resetFailedAttempts(rateLimitKey);
                log.info("✓ Single-use recovery code consumed for user: {}", user.getUsername());
                return true;
            }
        }

        recordFailedAttempt(rateLimitKey);
        return false;
    }

    public void invalidateAllRecoveryCodes(User user) {
        userRecoveryCodeRepository.deleteByUser(user);
    }

    private String hashRecoveryCode(String cleanCode) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(cleanCode.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Could not hash recovery code", e);
        }
    }
}
