package com.btctech.mailapp.service;

import com.btctech.mailapp.entity.BiometricCredential;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.entity.UserSettings;
import com.btctech.mailapp.repository.BiometricCredentialRepository;
import com.btctech.mailapp.repository.UserRepository;
import com.btctech.mailapp.repository.UserSettingsRepository;
import com.btctech.mailapp.util.WebAuthnCbor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECPoint;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class BiometricAuthenticationIntegrationTest {

    private BiometricAuthService biometricAuthService;
    private BiometricCredentialRepository credentialRepository;
    private UserRepository userRepository;
    private UserSettingsRepository userSettingsRepository;

    private List<BiometricCredential> credDatabase;
    private Map<String, User> userDatabase;
    private Map<Long, UserSettings> settingsDatabase;

    private User testUser;
    private KeyPair ecKeyPair;

    @BeforeEach
    void setUp() throws Exception {
        credDatabase = new ArrayList<>();
        userDatabase = new HashMap<>();
        settingsDatabase = new HashMap<>();

        testUser = new User();
        testUser.setId(101L);
        testUser.setUsername("bob");
        testUser.setEmail("bob@bnxmail.com");
        testUser.setBiometricsEnabled(false);
        userDatabase.put("bob@bnxmail.com", testUser);
        userDatabase.put("bob", testUser);

        UserSettings settings = new UserSettings();
        settings.setUser(testUser);
        settings.setBiometricsEnabled(false);
        settingsDatabase.put(testUser.getId(), settings);

        // Dynamic Proxy for BiometricCredentialRepository
        credentialRepository = (BiometricCredentialRepository) Proxy.newProxyInstance(
                BiometricCredentialRepository.class.getClassLoader(),
                new Class<?>[]{BiometricCredentialRepository.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("findByUserAndRevokedFalse".equals(name)) {
                        User u = (User) args[0];
                        return credDatabase.stream()
                                .filter(c -> c.getUser().getId().equals(u.getId()) && !Boolean.TRUE.equals(c.getRevoked()))
                                .toList();
                    }
                    if ("findByCredentialIdAndRevokedFalse".equals(name)) {
                        String cid = (String) args[0];
                        return credDatabase.stream()
                                .filter(c -> cid.equals(c.getCredentialId()) && !Boolean.TRUE.equals(c.getRevoked()))
                                .findFirst();
                    }
                    if ("findByCredentialId".equals(name)) {
                        String cid = (String) args[0];
                        return credDatabase.stream().filter(c -> cid.equals(c.getCredentialId())).findFirst();
                    }
                    if ("save".equals(name)) {
                        BiometricCredential c = (BiometricCredential) args[0];
                        if (c.getId() == null) c.setId((long) (credDatabase.size() + 1));
                        credDatabase.removeIf(existing -> existing.getId().equals(c.getId()));
                        credDatabase.add(c);
                        return c;
                    }
                    if ("saveAll".equals(name)) {
                        @SuppressWarnings("unchecked")
                        Iterable<BiometricCredential> list = (Iterable<BiometricCredential>) args[0];
                        for (BiometricCredential c : list) {
                            if (c.getId() == null) c.setId((long) (credDatabase.size() + 1));
                            credDatabase.removeIf(existing -> existing.getId().equals(c.getId()));
                            credDatabase.add(c);
                        }
                        return list;
                    }
                    if ("countByUserAndRevokedFalse".equals(name)) {
                        User u = (User) args[0];
                        return credDatabase.stream()
                                .filter(c -> c.getUser().getId().equals(u.getId()) && !Boolean.TRUE.equals(c.getRevoked()))
                                .count();
                    }
                    return null;
                }
        );

        // Dynamic Proxy for UserRepository
        userRepository = (UserRepository) Proxy.newProxyInstance(
                UserRepository.class.getClassLoader(),
                new Class<?>[]{UserRepository.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("findByEmail".equals(name) || "findByUsername".equals(name)) {
                        String id = ((String) args[0]).toLowerCase();
                        return Optional.ofNullable(userDatabase.get(id));
                    }
                    if ("save".equals(name)) {
                        User u = (User) args[0];
                        if (u.getEmail() != null) userDatabase.put(u.getEmail().toLowerCase(), u);
                        if (u.getUsername() != null) userDatabase.put(u.getUsername().toLowerCase(), u);
                        return u;
                    }
                    return null;
                }
        );

        // Dynamic Proxy for UserSettingsRepository
        userSettingsRepository = (UserSettingsRepository) Proxy.newProxyInstance(
                UserSettingsRepository.class.getClassLoader(),
                new Class<?>[]{UserSettingsRepository.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("findByUser".equals(name)) {
                        User u = (User) args[0];
                        return Optional.ofNullable(settingsDatabase.get(u.getId()));
                    }
                    if ("save".equals(name)) {
                        UserSettings s = (UserSettings) args[0];
                        if (s.getUser() != null) settingsDatabase.put(s.getUser().getId(), s);
                        return s;
                    }
                    return null;
                }
        );

        biometricAuthService = new BiometricAuthService(
                credentialRepository,
                userRepository,
                userSettingsRepository,
                null,
                null,
                null,
                null
        );

        ReflectionTestUtils.setField(biometricAuthService, "configuredRpId", "localhost");
        ReflectionTestUtils.setField(biometricAuthService, "rpName", "BNXmail");

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(256);
        ecKeyPair = kpg.generateKeyPair();
    }

    @Test
    @DisplayName("Scenario 1: Generate Registration Options")
    void testGenerateRegistrationOptions() {
        Map<String, Object> options = biometricAuthService.generateRegistrationOptions(testUser, "http://localhost:5173");

        assertNotNull(options.get("challenge"));
        assertEquals("BNXmail", ((Map<?, ?>) options.get("rp")).get("name"));
        assertEquals("localhost", ((Map<?, ?>) options.get("rp")).get("id"));

        Map<?, ?> userMap = (Map<?, ?>) options.get("user");
        assertEquals("bob@bnxmail.com", userMap.get("name"));

        Map<?, ?> authSelection = (Map<?, ?>) options.get("authenticatorSelection");
        assertEquals("platform", authSelection.get("authenticatorAttachment"));
    }

    @Test
    @DisplayName("Scenario 2: Verify Registration and Save Credential")
    void testVerifyAndRegisterSuccess() throws Exception {
        Map<String, Object> options = biometricAuthService.generateRegistrationOptions(testUser, "http://localhost:5173");
        String challenge = (String) options.get("challenge");

        String clientDataJson = "{\"type\":\"webauthn.create\",\"challenge\":\"" + challenge + "\",\"origin\":\"http://localhost:5173\"}";
        String clientDataJSONBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(clientDataJson.getBytes(StandardCharsets.UTF_8));

        byte[] rawCredId = "test_cred_id_12345".getBytes(StandardCharsets.UTF_8);
        byte[] attestationObjectBytes = buildSyntheticAttestationObject(ecKeyPair.getPublic(), rawCredId);
        String attestationObjectBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(attestationObjectBytes);
        String credIdBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(rawCredId);

        Map<String, Object> request = new HashMap<>();
        request.put("id", credIdBase64);
        request.put("rawId", credIdBase64);
        request.put("deviceName", "Windows Hello");
        request.put("response", Map.of(
                "clientDataJSON", clientDataJSONBase64,
                "attestationObject", attestationObjectBase64
        ));

        BiometricCredential saved = biometricAuthService.verifyAndRegister(testUser, request, "http://localhost:5173");

        assertNotNull(saved);
        assertEquals(credIdBase64, saved.getCredentialId());
        assertTrue(testUser.getBiometricsEnabled());
        assertEquals(1, credDatabase.size());
    }

    @Test
    @DisplayName("Scenario 3: Disable Biometrics Revokes Credentials")
    void testDisableBiometrics() {
        BiometricCredential cred = new BiometricCredential();
        cred.setId(1L);
        cred.setUser(testUser);
        cred.setCredentialId("cred_to_revoke");
        cred.setPublicKeyCose("MOCK_KEY");
        cred.setRevoked(false);
        credDatabase.add(cred);

        testUser.setBiometricsEnabled(true);
        userDatabase.put("bob@bnxmail.com", testUser);

        biometricAuthService.disableBiometrics(testUser);

        assertFalse(testUser.getBiometricsEnabled());
        assertTrue(cred.getRevoked());
    }

    @Test
    @DisplayName("Scenario 4: Cryptographic Signature Verification on WebAuthn Authentication")
    void testSignatureVerification() throws Exception {
        testUser.setBiometricsEnabled(true);
        String credIdBase64 = "bio_cred_login_test_123";

        BiometricCredential credential = new BiometricCredential();
        credential.setId(10L);
        credential.setUser(testUser);
        credential.setCredentialId(credIdBase64);
        credential.setAlgorithm(-7); // ES256
        credential.setSignCount(1L);
        credential.setPublicKeyCose(WebAuthnCbor.encodePublicKeyToX509Base64(ecKeyPair.getPublic()));
        credential.setRevoked(false);
        credDatabase.add(credential);

        Map<String, Object> loginOptions = biometricAuthService.generateLoginOptions("bob@bnxmail.com", "http://localhost:5173");
        String challenge = (String) loginOptions.get("challenge");
        assertNotNull(challenge);

        String clientDataJson = "{\"type\":\"webauthn.get\",\"challenge\":\"" + challenge + "\",\"origin\":\"http://localhost:5173\"}";
        byte[] clientDataJSONBytes = clientDataJson.getBytes(StandardCharsets.UTF_8);

        byte[] authData = new byte[37];
        byte[] rpIdHash = MessageDigest.getInstance("SHA-256").digest("localhost".getBytes(StandardCharsets.UTF_8));
        System.arraycopy(rpIdHash, 0, authData, 0, 32);
        authData[32] = 0x05; // UP + UV
        authData[36] = 2;    // signCount = 2

        // Compute valid signature
        byte[] clientDataHash = MessageDigest.getInstance("SHA-256").digest(clientDataJSONBytes);
        byte[] signedData = new byte[authData.length + clientDataHash.length];
        System.arraycopy(authData, 0, signedData, 0, authData.length);
        System.arraycopy(clientDataHash, 0, signedData, authData.length, clientDataHash.length);

        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(ecKeyPair.getPrivate());
        sig.update(signedData);
        byte[] signatureBytes = sig.sign();

        PublicKey pubKey = WebAuthnCbor.decodePublicKeyFromX509Base64(credential.getPublicKeyCose(), -7);
        boolean verified = WebAuthnCbor.verifySignature(pubKey, -7, authData, clientDataJSONBytes, signatureBytes);

        assertTrue(verified, "Cryptographic ES256 signature must verify successfully");
    }

    @Test
    @DisplayName("Scenario 5: Reject Invalid Biometric Signature")
    void testRejectInvalidSignature() throws Exception {
        testUser.setBiometricsEnabled(true);
        String credIdBase64 = "bio_cred_invalid_sig";

        BiometricCredential credential = new BiometricCredential();
        credential.setId(20L);
        credential.setUser(testUser);
        credential.setCredentialId(credIdBase64);
        credential.setAlgorithm(-7);
        credential.setSignCount(1L);
        credential.setPublicKeyCose(WebAuthnCbor.encodePublicKeyToX509Base64(ecKeyPair.getPublic()));

        byte[] authData = new byte[37];
        authData[32] = 0x01;
        byte[] clientDataBytes = "test_client_data".getBytes(StandardCharsets.UTF_8);

        byte[] bogusSig = new byte[]{0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01};

        PublicKey pubKey = WebAuthnCbor.decodePublicKeyFromX509Base64(credential.getPublicKeyCose(), -7);
        boolean verified = WebAuthnCbor.verifySignature(pubKey, -7, authData, clientDataBytes, bogusSig);

        assertFalse(verified, "Corrupted / invalid signature must be rejected");
    }

    private byte[] buildSyntheticAttestationObject(PublicKey pubKey, byte[] credId) throws Exception {
        ECPublicKey ecPub = (ECPublicKey) pubKey;
        ECPoint point = ecPub.getW();
        byte[] x = toUnsigned32ByteArray(point.getAffineX());
        byte[] y = toUnsigned32ByteArray(point.getAffineY());

        ByteArrayOutputStream coseOut = new ByteArrayOutputStream();
        coseOut.write(0xA5); // map of 5 pairs
        coseOut.write(0x01); coseOut.write(0x02); // kty: 2
        coseOut.write(0x03); coseOut.write(0x26); // alg: -7
        coseOut.write(0x20); coseOut.write(0x01); // crv: 1
        coseOut.write(0x21); coseOut.write(0x58); coseOut.write(32); coseOut.write(x);
        coseOut.write(0x22); coseOut.write(0x58); coseOut.write(32); coseOut.write(y);
        byte[] coseBytes = coseOut.toByteArray();

        ByteArrayOutputStream authDataOut = new ByteArrayOutputStream();
        authDataOut.write(MessageDigest.getInstance("SHA-256").digest("localhost".getBytes(StandardCharsets.UTF_8)));
        authDataOut.write(0x45); // UP | UV | AT
        authDataOut.write(new byte[]{0, 0, 0, 0});
        authDataOut.write(new byte[16]); // aaguid
        authDataOut.write((credId.length >> 8) & 0xFF);
        authDataOut.write(credId.length & 0xFF);
        authDataOut.write(credId);
        authDataOut.write(coseBytes);
        byte[] authData = authDataOut.toByteArray();

        ByteArrayOutputStream attOut = new ByteArrayOutputStream();
        attOut.write(0xA3);
        attOut.write(new byte[]{0x63, 'f', 'm', 't'});
        attOut.write(new byte[]{0x64, 'n', 'o', 'n', 'e'});
        attOut.write(new byte[]{0x67, 'a', 't', 't', 'S', 't', 'm', 't'});
        attOut.write(0xA0);
        attOut.write(new byte[]{0x68, 'a', 'u', 't', 'h', 'D', 'a', 't', 'a'});
        if (authData.length < 256) {
            attOut.write(0x58);
            attOut.write(authData.length);
        } else {
            attOut.write(0x59);
            attOut.write((authData.length >> 8) & 0xFF);
            attOut.write(authData.length & 0xFF);
        }
        attOut.write(authData);

        return attOut.toByteArray();
    }

    private byte[] toUnsigned32ByteArray(BigInteger bi) {
        byte[] raw = bi.toByteArray();
        if (raw.length == 32) return raw;
        byte[] out = new byte[32];
        if (raw.length > 32) {
            System.arraycopy(raw, raw.length - 32, out, 0, 32);
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        }
        return out;
    }
}
