package com.btctech.mailapp.controller;

import com.btctech.mailapp.dto.ApiResponse;
import com.btctech.mailapp.entity.AuthenticatorAccount;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.entity.UserSettings;
import com.btctech.mailapp.repository.AuthenticatorAccountRepository;
import com.btctech.mailapp.repository.UserRepository;
import com.btctech.mailapp.repository.UserSettingsRepository;
import com.btctech.mailapp.service.TwoFactorService;
import com.btctech.mailapp.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Transactional
@RestController
@RequestMapping("/api/users/2fa")
public class TwoFactorController {

    @Autowired
    private TwoFactorService twoFactorService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserSettingsRepository userSettingsRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private AuthenticatorAccountRepository authenticatorAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * Step 1: Initiate 2FA setup.
     * Generates a new TOTP secret (or reuses unverified pending setup secret to prevent race conditions),
     * encrypts it at rest in user entity (2FA not enabled yet),
     * and returns the base64 PNG data URI QR code + plain secret for display.
     */
    @PostMapping("/setup")
    public ResponseEntity<?> initiateSetup(@AuthenticationPrincipal String principal,
                                           @RequestParam(value = "refresh", required = false, defaultValue = "false") boolean refresh) {
        if (principal == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        }
        User user = userRepository.findByUsername(principal)
                .or(() -> userRepository.findByEmail(principal))
                .orElseThrow(() -> new RuntimeException("User not found"));

        String rawSecret = null;
        // Reuse unverified pending setup secret if one already exists and refresh is not forced
        if (!refresh && !Boolean.TRUE.equals(user.getTwoFactorEnabled()) && user.getTwoFactorSecret() != null) {
            try {
                rawSecret = twoFactorService.decryptSecret(user.getTwoFactorSecret());
            } catch (Exception e) {
                log.warn("Could not decrypt existing pending 2FA secret, generating new one: {}", e.getMessage());
            }
        }

        if (rawSecret == null || rawSecret.trim().isEmpty()) {
            rawSecret = twoFactorService.generateSecret();
            String encryptedSecret = twoFactorService.encryptSecret(rawSecret);
            user.setTwoFactorSecret(encryptedSecret);
            // Note: twoFactorEnabled remains FALSE until verified with a valid code
            user.setLastTotpStep(null);
            userRepository.save(user);
        }

        String label = (user.getEmail() != null && !user.getEmail().trim().isEmpty()) ? user.getEmail() : user.getUsername();
        String qrCodeUrl = twoFactorService.getQrCodeUrl(rawSecret, label);
        String otpauthUri = twoFactorService.getOtpauthUri(rawSecret, label);

        Map<String, Object> data = new HashMap<>();
        data.put("secret", rawSecret);
        data.put("qrCodeUrl", qrCodeUrl);
        data.put("otpauthUri", otpauthUri);

        log.info("2FA setup initiated/retrieved for user: {}", user.getUsername());
        return ResponseEntity.ok(ApiResponse.success(data, "2FA setup initiated"));
    }

    /**
     * Step 2: Verify initial OTP and enable 2FA.
     * Generates 10 single-use recovery codes upon successful verification.
     */
    @PostMapping("/verify")
    public ResponseEntity<?> verifyAndEnable(@AuthenticationPrincipal String principal, @RequestBody Map<String, String> body) {
        if (principal == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        }
        String code = body != null ? (body.get("code") != null ? body.get("code") : body.get("otp")) : null;
        if (code == null || code.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Verification code is required"));
        }

        String clientSecret = body != null ? body.get("secret") : null;

        User user = userRepository.findByUsername(principal)
                .or(() -> userRepository.findByEmail(principal))
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.getTwoFactorSecret() == null && (clientSecret == null || clientSecret.trim().isEmpty())) {
            return ResponseEntity.badRequest().body(ApiResponse.error("No pending 2FA setup found. Please initiate setup first."));
        }

        try {
            if (twoFactorService.verifyCode(user, code, clientSecret)) {
                // Authoritatively enable 2FA on User entity
                user.setTwoFactorEnabled(true);
                userRepository.save(user);

                // Synchronize UserSettings table to prevent desync
                UserSettings settings = userSettingsRepository.findByUser(user)
                        .orElseGet(() -> UserSettings.builder().user(user).build());
                settings.setTwoFactorEnabled(true);
                userSettingsRepository.save(settings);

                // Sync to authenticator accounts for BNX Auth
                String accountName = "BNX Auth (" + user.getEmail() + ")";
                authenticatorAccountRepository.deleteByUserIdAndAccountName(user.getId(), accountName);

                AuthenticatorAccount acc = new AuthenticatorAccount();
                acc.setUser(user);
                acc.setAccountName(accountName);
                acc.setSecretKey(user.getTwoFactorSecret());
                authenticatorAccountRepository.save(acc);

                // Generate 10 single-use recovery codes
                List<String> recoveryCodes = twoFactorService.generateRecoveryCodes(user, 10);

                Map<String, Object> data = new HashMap<>();
                data.put("recoveryCodes", recoveryCodes);
                data.put("twoFactorEnabled", true);

                log.info("✓ 2FA successfully verified and enabled for user: {}", user.getUsername());
                return ResponseEntity.ok(ApiResponse.success(data, "2FA enabled successfully"));
            } else {
                return ResponseEntity.badRequest().body(ApiResponse.error("Invalid verification code. Please try again."));
            }
        } catch (RuntimeException re) {
            return ResponseEntity.badRequest().body(ApiResponse.error(re.getMessage()));
        }
    }

    /**
     * Disable 2FA.
     * Requires re-authentication (account password or valid OTP/recovery code).
     */
    @PostMapping("/disable")
    public ResponseEntity<?> disable2FA(@AuthenticationPrincipal String principal, @RequestBody(required = false) Map<String, String> body) {
        if (principal == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        }
        User user = userRepository.findByUsername(principal)
                .or(() -> userRepository.findByEmail(principal))
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Re-authentication check
        String password = body != null ? body.get("password") : null;
        String code = body != null ? body.get("code") : null;
        boolean authenticated = false;

        if (password != null && !password.trim().isEmpty()) {
            authenticated = passwordEncoder.matches(password.trim(), user.getPassword());
        } else if (code != null && !code.trim().isEmpty()) {
            try {
                authenticated = twoFactorService.verifyCode(user, code.trim())
                        || twoFactorService.verifyAndConsumeRecoveryCode(user, code.trim());
            } catch (Exception ignored) {}
        }

        if (!authenticated) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Invalid password or verification code"));
        }

        // Authoritatively disable 2FA on User entity
        user.setTwoFactorEnabled(false);
        user.setTwoFactorSecret(null);
        user.setLastTotpStep(null);
        userRepository.save(user);

        // Synchronize UserSettings table
        UserSettings settings = userSettingsRepository.findByUser(user).orElse(null);
        if (settings != null) {
            settings.setTwoFactorEnabled(false);
            userSettingsRepository.save(settings);
        }

        // Invalidate all recovery codes
        twoFactorService.invalidateAllRecoveryCodes(user);

        // Remove synced authenticator account
        String accountName = "BNX Auth (" + user.getEmail() + ")";
        authenticatorAccountRepository.deleteByUserIdAndAccountName(user.getId(), accountName);

        log.info("✓ 2FA disabled for user: {}", user.getUsername());
        return ResponseEntity.ok(ApiResponse.success(null, "2FA disabled successfully"));
    }

    @PostMapping("/enable")
    public ResponseEntity<?> enable2FA(@AuthenticationPrincipal String principal) {
        if (principal == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        }
        User user = userRepository.findByUsername(principal)
                .or(() -> userRepository.findByEmail(principal))
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.getTwoFactorSecret() == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("No 2FA setup found. Please set up 2FA first."));
        }

        user.setTwoFactorEnabled(true);
        userRepository.save(user);

        UserSettings settings = userSettingsRepository.findByUser(user).orElse(null);
        if (settings != null) {
            settings.setTwoFactorEnabled(true);
            userSettingsRepository.save(settings);
        }

        return ResponseEntity.ok(ApiResponse.success(null, "2FA enabled successfully"));
    }

    /**
     * Get authenticator accounts with masked/sanitized secrets
     */
    @GetMapping("/accounts")
    public ResponseEntity<?> getAuthenticatorAccounts(@AuthenticationPrincipal String principal) {
        if (principal == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        }
        User user = userRepository.findByUsername(principal)
                .or(() -> userRepository.findByEmail(principal))
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<AuthenticatorAccount> accounts = authenticatorAccountRepository.findByUser(user);
        String bnxAuthName = "BNX Auth (" + user.getEmail() + ")";

        List<Map<String, Object>> safeAccounts = new ArrayList<>();
        for (AuthenticatorAccount acc : accounts) {
            if (!Boolean.TRUE.equals(user.getTwoFactorEnabled()) && bnxAuthName.equals(acc.getAccountName())) {
                continue;
            }
            Map<String, Object> map = new HashMap<>();
            map.put("id", acc.getId());
            map.put("accountName", acc.getAccountName());
            map.put("createdAt", acc.getCreatedAt());
            // Note: secretKey is deliberately NOT returned to prevent secret exposure
            safeAccounts.add(map);
        }

        return ResponseEntity.ok(ApiResponse.success(safeAccounts, "Authenticator accounts retrieved"));
    }

    @PostMapping("/accounts")
    public ResponseEntity<?> addAuthenticatorAccount(@AuthenticationPrincipal String principal, @RequestBody Map<String, String> body) {
        String name = body.get("name");
        String secret = body.get("secret");

        if (principal == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        }
        User user = userRepository.findByUsername(principal)
                .or(() -> userRepository.findByEmail(principal))
                .orElseThrow(() -> new RuntimeException("User not found"));

        AuthenticatorAccount acc = new AuthenticatorAccount();
        acc.setUser(user);
        acc.setAccountName(name);
        acc.setSecretKey(twoFactorService.encryptSecret(secret));
        authenticatorAccountRepository.save(acc);

        return ResponseEntity.ok(ApiResponse.success(null, "Account added successfully"));
    }

    @DeleteMapping("/accounts/{id}")
    public ResponseEntity<?> deleteAuthenticatorAccount(@AuthenticationPrincipal String principal, @PathVariable Long id) {
        if (principal == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        }
        User user = userRepository.findByUsername(principal)
                .or(() -> userRepository.findByEmail(principal))
                .orElseThrow(() -> new RuntimeException("User not found"));

        AuthenticatorAccount acc = authenticatorAccountRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Account not found"));

        if (!acc.getUser().getId().equals(user.getId())) {
            return ResponseEntity.status(403).body(ApiResponse.error("Forbidden"));
        }

        authenticatorAccountRepository.delete(acc);
        return ResponseEntity.ok(ApiResponse.success(null, "Account deleted successfully"));
    }
}
