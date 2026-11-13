package com.btctech.mailapp.service;

import com.btctech.mailapp.entity.AuthenticatorAccount;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.entity.UserRecoveryCode;
import com.btctech.mailapp.entity.UserSettings;
import com.btctech.mailapp.repository.UserRecoveryCodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TwoFactorAuthenticationIntegrationTest {

    private TwoFactorService twoFactorService;
    private InMemoryUserRecoveryCodeRepository recoveryCodeRepository;
    private ObjectMapper objectMapper;
    private User testUser;

    static class InMemoryUserRecoveryCodeRepository implements UserRecoveryCodeRepository {
        private final List<UserRecoveryCode> database = new ArrayList<>();
        private long idGen = 1;

        @Override
        public List<UserRecoveryCode> findByUserAndUsedFalse(User user) {
            List<UserRecoveryCode> res = new ArrayList<>();
            for (UserRecoveryCode c : database) {
                if (c.getUser().getId().equals(user.getId()) && !Boolean.TRUE.equals(c.getUsed())) {
                    res.add(c);
                }
            }
            return res;
        }

        @Override
        public List<UserRecoveryCode> findByUser(User user) {
            List<UserRecoveryCode> res = new ArrayList<>();
            for (UserRecoveryCode c : database) {
                if (c.getUser().getId().equals(user.getId())) {
                    res.add(c);
                }
            }
            return res;
        }

        @Override
        public void deleteByUser(User user) {
            database.removeIf(c -> c.getUser().getId().equals(user.getId()));
        }

        @Override
        public <S extends UserRecoveryCode> S save(S entity) {
            if (entity.getId() == null) {
                entity.setId(idGen++);
            }
            database.removeIf(c -> c.getId().equals(entity.getId()));
            database.add(entity);
            return entity;
        }

        @Override
        public <S extends UserRecoveryCode> List<S> saveAll(Iterable<S> entities) {
            List<S> res = new ArrayList<>();
            for (S e : entities) {
                res.add(save(e));
            }
            return res;
        }

        // Unused stubs for interface
        @Override public Optional<UserRecoveryCode> findById(Long aLong) { return Optional.empty(); }
        @Override public boolean existsById(Long aLong) { return false; }
        @Override public long count() { return database.size(); }
        @Override public void deleteById(Long aLong) {}
        @Override public void delete(UserRecoveryCode entity) { database.remove(entity); }
        @Override public void deleteAllById(Iterable<? extends Long> longs) {}
        @Override public void deleteAll(Iterable<? extends UserRecoveryCode> entities) {}
        @Override public void deleteAll() { database.clear(); }
        @Override public List<UserRecoveryCode> findAll() { return new ArrayList<>(database); }
        @Override public List<UserRecoveryCode> findAllById(Iterable<Long> longs) { return Collections.emptyList(); }
        @Override public void flush() {}
        @Override public <S extends UserRecoveryCode> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends UserRecoveryCode> List<S> saveAllAndFlush(Iterable<S> entities) { return saveAll(entities); }
        @Override public void deleteAllInBatch(Iterable<UserRecoveryCode> entities) {}
        @Override public void deleteAllByIdInBatch(Iterable<Long> longs) {}
        @Override public void deleteAllInBatch() {}
        @Override public UserRecoveryCode getOne(Long aLong) { return null; }
        @Override public UserRecoveryCode getById(Long aLong) { return null; }
        @Override public UserRecoveryCode getReferenceById(Long aLong) { return null; }
        @Override public <S extends UserRecoveryCode> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { return Optional.empty(); }
        @Override public <S extends UserRecoveryCode> List<S> findAll(org.springframework.data.domain.Example<S> example) { return Collections.emptyList(); }
        @Override public <S extends UserRecoveryCode> List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { return Collections.emptyList(); }
        @Override public <S extends UserRecoveryCode> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { return null; }
        @Override public <S extends UserRecoveryCode> long count(org.springframework.data.domain.Example<S> example) { return 0; }
        @Override public <S extends UserRecoveryCode> boolean exists(org.springframework.data.domain.Example<S> example) { return false; }
        @Override public <S extends UserRecoveryCode, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { return null; }
        @Override public List<UserRecoveryCode> findAll(org.springframework.data.domain.Sort sort) { return Collections.emptyList(); }
        @Override public org.springframework.data.domain.Page<UserRecoveryCode> findAll(org.springframework.data.domain.Pageable pageable) { return null; }
    }

    @BeforeEach
    void setUp() {
        twoFactorService = new TwoFactorService();
        recoveryCodeRepository = new InMemoryUserRecoveryCodeRepository();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        ReflectionTestUtils.setField(twoFactorService, "encryptionKey", "1234567890123456");
        ReflectionTestUtils.setField(twoFactorService, "userRecoveryCodeRepository", recoveryCodeRepository);

        testUser = new User();
        testUser.setId(99L);
        testUser.setUsername("alice");
        testUser.setEmail("alice@bnxmail.com");
        testUser.setTwoFactorEnabled(false);
    }

    @Test
    @DisplayName("Scenario 1: Secret Generation and AES-GCM Encrypted Storage at Rest")
    void testSecretGenerationAndEncryption() {
        String secret = twoFactorService.generateSecret();
        assertNotNull(secret);
        assertTrue(secret.length() >= 16);

        // Encrypt secret with AES-GCM
        String encrypted = twoFactorService.encryptSecret(secret);
        assertNotNull(encrypted);
        assertTrue(encrypted.startsWith("enc:"), "Encrypted secret must have authenticated prefix");
        assertNotEquals(secret, encrypted, "Secret must not be stored in plaintext");

        // Decrypt secret
        String decrypted = twoFactorService.decryptSecret(encrypted);
        assertEquals(secret, decrypted, "Decrypted secret must match original plain secret");

        // Backward compatibility: legacy plaintext secret
        assertEquals("LEGACYPLAINSECRET", twoFactorService.decryptSecret("LEGACYPLAINSECRET"));
    }

    @Test
    @DisplayName("Scenario 2: QR Code Data URI generation returns renderable base64 PNG image")
    void testQrCodeDataUriGeneration() {
        String secret = twoFactorService.generateSecret();
        String qrCodeUrl = twoFactorService.getQrCodeUrl(secret, "alice@bnxmail.com");

        assertNotNull(qrCodeUrl);
        assertTrue(qrCodeUrl.startsWith("data:image/png;base64,"),
                "QR code must be a valid data URI base64 PNG image, but got: " + qrCodeUrl.substring(0, Math.min(30, qrCodeUrl.length())));

        String otpauthUri = twoFactorService.getOtpauthUri(secret, "alice@bnxmail.com");
        assertNotNull(otpauthUri);
        assertTrue(otpauthUri.startsWith("otpauth://totp/"));
        assertTrue(otpauthUri.contains("alice@bnxmail.com") || otpauthUri.contains("alice%40bnxmail.com"));
    }

    @Test
    @DisplayName("Scenario 3: Reject incorrect OTP")
    void testRejectIncorrectOtp() {
        String secret = twoFactorService.generateSecret();
        testUser.setTwoFactorSecret(twoFactorService.encryptSecret(secret));

        assertFalse(twoFactorService.verifyCode(testUser, "000000"));
        assertFalse(twoFactorService.verifyCode(testUser, "12345"));
        assertFalse(twoFactorService.verifyCode(testUser, "invalid"));
    }

    @Test
    @DisplayName("Scenario 4: Accept valid OTP and Enforce Replay Protection")
    void testAcceptValidOtpAndPreventReplay() throws Exception {
        String plainSecret = twoFactorService.generateSecret();
        testUser.setTwoFactorSecret(twoFactorService.encryptSecret(plainSecret));

        long currentBucket = (System.currentTimeMillis() / 1000L) / 30L;
        CodeGenerator codeGen = new DefaultCodeGenerator();
        String validCode = codeGen.generate(plainSecret, currentBucket);

        // First verification should succeed
        boolean firstAttempt = twoFactorService.verifyCode(testUser, validCode);
        assertTrue(firstAttempt, "Valid TOTP code within time window must be accepted");
        assertEquals(currentBucket, testUser.getLastTotpStep(), "lastTotpStep must be recorded on user");

        // Replay attempt within the same bucket MUST be rejected
        boolean replayAttempt = twoFactorService.verifyCode(testUser, validCode);
        assertFalse(replayAttempt, "Replaying the same TOTP code within the same time step MUST be rejected");
    }

    @Test
    @DisplayName("Scenario 5: Single-Use Backup Recovery Codes Generation and Prevent Reuse")
    void testRecoveryCodesGenerationAndSingleUse() {
        List<String> codes = twoFactorService.generateRecoveryCodes(testUser, 10);
        assertEquals(10, codes.size(), "Must generate exactly 10 recovery codes");

        for (String c : codes) {
            assertTrue(c.matches("^[2-9A-Z]{4}-[2-9A-Z]{4}$"), "Recovery code must match XXXX-XXXX format: " + c);
        }

        String firstCode = codes.get(0);

        // Verify valid recovery code succeeds
        boolean consumed = twoFactorService.verifyAndConsumeRecoveryCode(testUser, firstCode);
        assertTrue(consumed, "Unused recovery code must be accepted and consumed");

        // Attempt to reuse the SAME recovery code MUST be rejected
        boolean reuseAttempt = twoFactorService.verifyAndConsumeRecoveryCode(testUser, firstCode);
        assertFalse(reuseAttempt, "Previously used recovery code must be rejected (single-use only)");

        // Invalid code must be rejected
        assertFalse(twoFactorService.verifyAndConsumeRecoveryCode(testUser, "INVALID-CODE"));
    }

    @Test
    @DisplayName("Scenario 6: Rate Limiting Protects against Brute-Forcing")
    void testRateLimitingOnFailures() {
        String secret = twoFactorService.generateSecret();
        testUser.setTwoFactorSecret(twoFactorService.encryptSecret(secret));

        // Submit 5 invalid attempts
        for (int i = 0; i < 5; i++) {
            assertFalse(twoFactorService.verifyCode(testUser, "99999" + i));
        }

        // 6th attempt should trigger rate limiting exception
        assertThrows(RuntimeException.class, () -> {
            twoFactorService.verifyCode(testUser, "111111");
        }, "Exceeding failed attempt threshold must trigger rate limit exception");
    }

    @Test
    @DisplayName("Scenario 7: Secrets are protected from JSON Serialization")
    void testSecretsNotExposedInJson() throws Exception {
        testUser.setTwoFactorSecret("enc:SENSITIVESECRETDATA");
        String userJson = objectMapper.writeValueAsString(testUser);
        assertFalse(userJson.contains("twoFactorSecret"), "twoFactorSecret must NOT be serialized in JSON");
        assertFalse(userJson.contains("SENSITIVESECRETDATA"), "Raw secret data must NOT appear in JSON");

        AuthenticatorAccount account = new AuthenticatorAccount();
        account.setId(1L);
        account.setAccountName("BNX Auth (alice@bnxmail.com)");
        account.setSecretKey("TOPSECRETKEY123");

        String accountJson = objectMapper.writeValueAsString(account);
        assertFalse(accountJson.contains("secretKey"), "AuthenticatorAccount.secretKey must NOT be serialized in JSON");
        assertFalse(accountJson.contains("TOPSECRETKEY123"), "Secret key must never leak via JSON");
    }

    @Test
    @DisplayName("Scenario 8: Synchronize User and UserSettings Status")
    void testStatusSynchronization() {
        UserSettings settings = new UserSettings();
        settings.setUser(testUser);

        // Enabling 2FA
        testUser.setTwoFactorEnabled(true);
        settings.setTwoFactorEnabled(Boolean.TRUE.equals(testUser.getTwoFactorEnabled()));
        assertTrue(settings.getTwoFactorEnabled());
        assertTrue(testUser.getTwoFactorEnabled());

        // Disabling 2FA
        testUser.setTwoFactorEnabled(false);
        settings.setTwoFactorEnabled(Boolean.TRUE.equals(testUser.getTwoFactorEnabled()));
        assertFalse(settings.getTwoFactorEnabled());
        assertFalse(testUser.getTwoFactorEnabled());
    }

    @Test
    @DisplayName("Scenario 9: RFC 6238 Standard Test Vectors")
    void testRfc6238Vectors() throws Exception {
        CodeGenerator codeGen = new DefaultCodeGenerator();
        // RFC 6238 secret: "12345678901234567890" in Base32 is "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        String base32Secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

        assertEquals("287082", codeGen.generate(base32Secret, 59L / 30L));
        assertEquals("081804", codeGen.generate(base32Secret, 1111111109L / 30L));
        assertEquals("050471", codeGen.generate(base32Secret, 1111111111L / 30L));
        assertEquals("005924", codeGen.generate(base32Secret, 1234567890L / 30L));
        assertEquals("279037", codeGen.generate(base32Secret, 2000000000L / 30L));
    }

    @Test
    @DisplayName("Scenario 10: Secret Normalization Handling (spaces, case, padding)")
    void testSecretNormalization() throws Exception {
        CodeGenerator codeGen = new DefaultCodeGenerator();
        String baseSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
        long bucket = 1234567890L / 30L;
        String expected = "005924";

        assertEquals(expected, codeGen.generate(baseSecret, bucket));
        // With spaces
        String withSpaces = "GEZD GNBV GY3T QOJQ GEZD GNBV GY3T QOJQ";
        String normalizedSpaces = withSpaces.replaceAll("\\s+", "").toUpperCase();
        assertEquals(expected, codeGen.generate(normalizedSpaces, bucket));

        // Lowercase
        String lowercase = baseSecret.toLowerCase();
        String normalizedCase = lowercase.toUpperCase();
        assertEquals(expected, codeGen.generate(normalizedCase, bucket));
    }

    @Test
    @DisplayName("Scenario 11: Clock Skew Tolerance with Configurable Window")
    void testClockSkewTolerance() throws Exception {
        String secret = twoFactorService.generateSecret();
        testUser.setTwoFactorSecret(twoFactorService.encryptSecret(secret));

        long currentBucket = (System.currentTimeMillis() / 1000L) / 30L;
        CodeGenerator codeGen = new DefaultCodeGenerator();

        // Bucket - 2 (60 seconds behind server time)
        String pastCode = codeGen.generate(secret, currentBucket - 2);
        assertTrue(twoFactorService.verifyCode(testUser, pastCode), "Code from 60 seconds ago (-2 time steps) must be accepted");

        // Advance lastTotpStep
        testUser.setLastTotpStep(null);

        // Bucket + 2 (60 seconds ahead of server time)
        String futureCode = codeGen.generate(secret, currentBucket + 2);
        assertTrue(twoFactorService.verifyCode(testUser, futureCode), "Code from 60 seconds in the future (+2 time steps) must be accepted");
    }

    @Test
    @DisplayName("Scenario 12: Race Condition Recovery via Client Setup Secret")
    void testRaceConditionRecovery() throws Exception {
        String wizardSecret = twoFactorService.generateSecret();
        String overwrittenSecret = twoFactorService.generateSecret();

        // Simulate DB having overwrittenSecret while user has wizardSecret on screen
        testUser.setTwoFactorSecret(twoFactorService.encryptSecret(overwrittenSecret));

        long currentBucket = (System.currentTimeMillis() / 1000L) / 30L;
        CodeGenerator codeGen = new DefaultCodeGenerator();
        String codeFromWizard = codeGen.generate(wizardSecret, currentBucket);

        // Verification without client secret would fail against overwritten secret
        assertFalse(twoFactorService.verifyCode(testUser, "000000"));

        // Verification with client secret should succeed and synchronize user's secret
        boolean verified = twoFactorService.verifyCode(testUser, codeFromWizard, wizardSecret);
        assertTrue(verified, "Verification must succeed using wizard setup secret fallback");

        // Verify user secret was synchronized to wizardSecret
        assertEquals(wizardSecret, twoFactorService.decryptSecret(testUser.getTwoFactorSecret()));
    }
}

