package com.btctech.mailapp.controller;

import com.btctech.mailapp.dto.ApiResponse;
import com.btctech.mailapp.dto.LoginResponseData;
import com.btctech.mailapp.entity.BiometricCredential;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.repository.UserRepository;
import com.btctech.mailapp.service.BiometricAuthService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth/biometric")
@RequiredArgsConstructor
public class BiometricController {

    private final BiometricAuthService biometricAuthService;
    private final UserRepository userRepository;
    private final com.btctech.mailapp.config.JwtUtil jwtUtil;

    /**
     * Get biometric status for current authenticated user
     */
    @GetMapping("/status")
    public ResponseEntity<?> getBiometricStatus(@AuthenticationPrincipal String principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Unauthorized"));
        }
        User user = userRepository.findByUsername(principal)
                .or(() -> userRepository.findByEmail(principal))
                .orElseThrow(() -> new RuntimeException("User not found"));

        Map<String, Object> status = biometricAuthService.getBiometricStatus(user);
        return ResponseEntity.ok(ApiResponse.success(status, "Biometric status retrieved"));
    }

    /**
     * Step 1 (Registration): Generate WebAuthn options for registering biometric platform authenticator
     */
    @PostMapping("/register/options")
    public ResponseEntity<?> getRegistrationOptions(@AuthenticationPrincipal String principal,
                                                   HttpServletRequest request) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Unauthorized"));
        }
        User user = userRepository.findByUsername(principal)
                .or(() -> userRepository.findByEmail(principal))
                .orElseThrow(() -> new RuntimeException("User not found"));

        String origin = request.getHeader("Origin");
        Map<String, Object> options = biometricAuthService.generateRegistrationOptions(user, origin);
        return ResponseEntity.ok(ApiResponse.success(options, "Registration options generated"));
    }

    /**
     * Step 2 (Registration): Verify attestation response and enable biometrics
     */
    @PostMapping("/register/verify")
    public ResponseEntity<?> verifyRegistration(@AuthenticationPrincipal String principal,
                                                @RequestBody Map<String, Object> body,
                                                HttpServletRequest request) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Unauthorized"));
        }
        User user = userRepository.findByUsername(principal)
                .or(() -> userRepository.findByEmail(principal))
                .orElseThrow(() -> new RuntimeException("User not found"));

        try {
            String origin = request.getHeader("Origin");
            BiometricCredential credential = biometricAuthService.verifyAndRegister(user, body, origin);
            return ResponseEntity.ok(ApiResponse.success(
                    Map.of(
                            "biometricsEnabled", true,
                            "credentialId", credential.getCredentialId(),
                            "deviceName", credential.getDeviceName()
                    ),
                    "Biometric Authentication Enabled Successfully"
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Biometric registration failed for user {}: {}", user.getUsername(), e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error during biometric registration for user {}: {}", user.getUsername(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to complete biometric registration: " + e.getMessage()));
        }
    }

    /**
     * Disable biometric authentication for current user
     */
    @PostMapping("/disable")
    public ResponseEntity<?> disableBiometrics(@AuthenticationPrincipal String principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Unauthorized"));
        }
        User user = userRepository.findByUsername(principal)
                .or(() -> userRepository.findByEmail(principal))
                .orElseThrow(() -> new RuntimeException("User not found"));

        biometricAuthService.disableBiometrics(user);
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("biometricsEnabled", false),
                "Biometric authentication disabled successfully"
        ));
    }

    /**
     * Step 1 (Login): Generate assertion options for biometric login
     */
    @PostMapping("/login/options")
    public ResponseEntity<?> getLoginOptions(@RequestBody(required = false) Map<String, String> body,
                                             HttpServletRequest request) {
        String email = body != null ? body.get("email") : null;
        String tempToken = body != null ? body.get("tempToken") : null;
        if ((email == null || email.trim().isEmpty()) && tempToken != null) {
            try {
                String sub = jwtUtil.extractEmail(tempToken);
                email = sub.replace("bio_", "").replace("2fa_", "");
            } catch (Exception e) {
                log.warn("Invalid tempToken in login options: {}", e.getMessage());
            }
        }

        try {
            String origin = request.getHeader("Origin");
            Map<String, Object> options = biometricAuthService.generateLoginOptions(
                    (email != null && !email.trim().isEmpty()) ? email.trim() : null,
                    origin
            );
            return ResponseEntity.ok(ApiResponse.success(options, "Biometric login options generated"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Biometric login options generation failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error generating biometric login options: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to initialize biometric login"));
        }
    }

    /**
     * Step 2 (Login): Verify assertion signature and log the user in
     */
    @PostMapping("/login/verify")
    public ResponseEntity<?> verifyLogin(@RequestBody Map<String, Object> body,
                                         HttpServletRequest request) {
        if (body == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Authentication payload is required"));
        }

        String ipAddress = request.getHeader("X-Forwarded-For");
        if (ipAddress == null || ipAddress.isEmpty()) {
            ipAddress = request.getRemoteAddr();
        } else {
            ipAddress = ipAddress.split(",")[0].trim();
        }
        String userAgent = request.getHeader("User-Agent");
        String origin = request.getHeader("Origin");

        try {
            LoginResponseData loginData = biometricAuthService.verifyLogin(body, origin, ipAddress, userAgent);
            return ResponseEntity.ok(ApiResponse.success(loginData, "Biometric login successful"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Biometric verification rejected: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error verifying biometric login: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Biometric verification failed: " + e.getMessage()));
        }
    }
}

