package com.btctech.mailapp.controller;

import com.btctech.mailapp.config.JwtUtil;
import com.btctech.mailapp.dto.ApiResponse;
import com.btctech.mailapp.dto.OAuthAuthorizeRequest;
import com.btctech.mailapp.dto.OAuthTokenRequest;
import com.btctech.mailapp.service.OAuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/oauth")
@RequiredArgsConstructor
public class OAuthController {

    private final OAuthService oAuthService;
    private final JwtUtil jwtUtil;
    private final com.btctech.mailapp.repository.ExternalAppSessionRepository externalAppSessionRepository;
    private final com.btctech.mailapp.service.AuthService authService;
    private final com.btctech.mailapp.service.UserService userService;

    /**
     * Authenticated endpoint called by Auth UI after user logs in.
     * Returns an authorization code.
     */
    @PostMapping("/authorize")
    public ResponseEntity<ApiResponse<Map<String, String>>> authorize(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody OAuthAuthorizeRequest request) {

        String token = authHeader.substring(7);
        String email = jwtUtil.extractEmail(token);

        log.info("Generating auth code for client: {} and user: {}", request.getClientId(), email);

        String code = oAuthService.generateAuthorizationCode(
                request.getClientId(),
                request.getRedirectUri(),
                email
        );

        Map<String, String> data = new HashMap<>();
        data.put("code", code);
        data.put("state", request.getState());

        return ResponseEntity.ok(ApiResponse.success(data, "Authorization code generated"));
    }

    /**
     * Public endpoint called by Client App backend to exchange code for token.
     */
    @PostMapping("/token")
    public ResponseEntity<ApiResponse<Map<String, String>>> token(
            @Valid @RequestBody OAuthTokenRequest request,
            jakarta.servlet.http.HttpServletRequest httpRequest) {

        log.info("Token exchange request for client: {}", request.getClientId());

        if (!"authorization_code".equals(request.getGrantType())) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Unsupported grant type"));
        }

        // Extract metadata
        String ipAddress = httpRequest.getHeader("X-Forwarded-For");
        if (ipAddress == null || ipAddress.isEmpty()) {
            ipAddress = httpRequest.getRemoteAddr();
        } else {
            ipAddress = ipAddress.split(",")[0].trim();
        }

        String userAgent = httpRequest.getHeader("User-Agent");

        String accessToken = oAuthService.exchangeCodeForToken(
                request.getCode(),
                request.getClientId(),
                request.getClientSecret(),
                ipAddress,
                userAgent
        );

        Map<String, String> data = new HashMap<>();
        data.put("access_token", accessToken);
        data.put("token_type", "Bearer");

        return ResponseEntity.ok(ApiResponse.success(data, "Token generated successfully"));
    }

    /**
     * Check if an OAuth session / token is still active or revoked.
     * Used by client apps (Beta Website, Beta Storage, etc.) for session heartbeats and active check.
     */
    @GetMapping("/check-session")
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkSession(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(401).body(ApiResponse.error("Missing or invalid authorization token"));
        }

        String token = authHeader.substring(7);
        try {
            var claims = jwtUtil.extractAllClaims(token);
            String email = jwtUtil.extractEmail(token);
            Object sessionIdObj = claims.get("session_id");

            if (sessionIdObj != null) {
                Long sessionId = Long.valueOf(sessionIdObj.toString());
                var sessionOpt = externalAppSessionRepository.findById(sessionId);
                if (sessionOpt.isEmpty() || Boolean.TRUE.equals(sessionOpt.get().getRevoked())) {
                    log.warn("OAuth session {} is marked revoked or removed", sessionId);
                    return ResponseEntity.status(401).body(ApiResponse.error("Your session has been revoked. Please log in again."));
                }
            }

            Map<String, Object> data = new HashMap<>();
            data.put("active", true);
            data.put("email", email);
            data.put("sessionId", sessionIdObj);
            data.put("clientId", claims.get("client_id"));
            data.put("appName", claims.get("app_name"));
            return ResponseEntity.ok(ApiResponse.success(data, "Session is active"));
        } catch (Exception e) {
            log.warn("Token check failed: {}", e.getMessage());
            return ResponseEntity.status(401).body(ApiResponse.error("Session token is invalid or expired"));
        }
    }

    /**
     * RFC 7662 OAuth 2.0 Token Introspection.
     */
    @PostMapping("/introspect")
    public ResponseEntity<Map<String, Object>> introspect(
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        String token = body != null ? body.get("token") : null;
        if ((token == null || token.isEmpty()) && authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        }

        Map<String, Object> result = new HashMap<>();
        if (token == null || token.isEmpty()) {
            result.put("active", false);
            return ResponseEntity.ok(result);
        }

        try {
            var claims = jwtUtil.extractAllClaims(token);
            String email = jwtUtil.extractEmail(token);
            Object sessionIdObj = claims.get("session_id");

            if (sessionIdObj != null) {
                Long sessionId = Long.valueOf(sessionIdObj.toString());
                var sessionOpt = externalAppSessionRepository.findById(sessionId);
                if (sessionOpt.isEmpty() || Boolean.TRUE.equals(sessionOpt.get().getRevoked())) {
                    result.put("active", false);
                    result.put("error", "session_revoked");
                    result.put("message", "Your session has been revoked. Please log in again.");
                    return ResponseEntity.ok(result);
                }
            }

            result.put("active", true);
            result.put("sub", email);
            result.put("client_id", claims.get("client_id"));
            result.put("app_name", claims.get("app_name"));
            result.put("session_id", sessionIdObj);
            result.put("exp", claims.getExpiration() != null ? claims.getExpiration().getTime() / 1000 : null);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("active", false);
            return ResponseEntity.ok(result);
        }
    }

    /**
     * RFC 7009 OAuth 2.0 Token Revocation.
     */
    @PostMapping("/revoke")
    public ResponseEntity<ApiResponse<Void>> revokeToken(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        String token = body != null && body.containsKey("token") ? (String) body.get("token") : null;
        Object sessionIdObj = body != null ? body.get("sessionId") : null;

        if (token == null && authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        }

        Long sessionId = null;
        if (sessionIdObj != null) {
            sessionId = Long.valueOf(sessionIdObj.toString());
        } else if (token != null && !token.isEmpty()) {
            try {
                var claims = jwtUtil.extractAllClaims(token);
                if (claims.get("session_id") != null) {
                    sessionId = Long.valueOf(claims.get("session_id").toString());
                }
            } catch (Exception ignored) {}
        }

        if (sessionId != null) {
            var sessionOpt = externalAppSessionRepository.findById(sessionId);
            if (sessionOpt.isPresent()) {
                var s = sessionOpt.get();
                s.setRevoked(true);
                s.setRevokedAt(java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata")));
                externalAppSessionRepository.save(s);
                authService.broadcastSessionRevocation(s, s.getUser());
                log.info("✓ Revoked token/session {} via /api/oauth/revoke", sessionId);
            }
        }

        return ResponseEntity.ok(ApiResponse.success(null, "Token revoked successfully"));
    }

    /**
     * Standard UserInfo endpoint for connected apps.
     */
    @GetMapping("/userinfo")
    public ResponseEntity<ApiResponse<Map<String, Object>>> userInfo(
            @RequestHeader("Authorization") String authHeader) {

        String token = authHeader.substring(7);
        String email = jwtUtil.extractEmail(token);
        var user = userService.getUserByEmailOrUsername(email);
        if (user == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("User not found"));
        }

        Map<String, Object> data = new HashMap<>();
        data.put("sub", user.getEmail());
        data.put("email", user.getEmail());
        data.put("username", user.getUsername());
        data.put("firstName", user.getFirstName());
        data.put("lastName", user.getLastName());
        return ResponseEntity.ok(ApiResponse.success(data, "User info retrieved"));
    }
}
