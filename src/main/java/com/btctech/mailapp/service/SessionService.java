package com.btctech.mailapp.service;

import com.btctech.mailapp.entity.UserSession;
import com.btctech.mailapp.exception.MailException;
import com.btctech.mailapp.repository.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.time.LocalDateTime;
import java.util.Base64;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {
    
    private final UserSessionRepository sessionRepository;
    private final com.btctech.mailapp.config.JwtUtil jwtUtil;
    private final com.btctech.mailapp.repository.UserRepository userRepository;
    private final com.btctech.mailapp.repository.MailAccountRepository mailAccountRepository;
    
    @Value("${encryption.key:1234567890123456}")
    private String encryptionKey;
    
    /**
     * Create session with encrypted password
     * Each user gets their own session
     */
    @Transactional
    public UserSession createSession(Long userId, Long mailAccountId, 
                                     String password, String jwtToken) {
        try {
            // Check if session already exists for this token
            sessionRepository.findByJwtToken(jwtToken).ifPresent(existing -> {
                log.info("Removing existing session for token");
                sessionRepository.delete(existing);
            });
            
            // Encrypt password
            String encryptedPassword = encrypt(password);
            
            // Extract request details (IP, User Agent)
            String ipAddress = null;
            String userAgent = null;
            String location = null;
            try {
                org.springframework.web.context.request.ServletRequestAttributes attributes = 
                    (org.springframework.web.context.request.ServletRequestAttributes) org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
                if (attributes != null) {
                    jakarta.servlet.http.HttpServletRequest request = attributes.getRequest();
                    ipAddress = request.getHeader("X-Forwarded-For");
                    if (ipAddress == null || ipAddress.isEmpty()) {
                        ipAddress = request.getRemoteAddr();
                    } else {
                        ipAddress = ipAddress.split(",")[0].trim();
                    }
                    userAgent = request.getHeader("User-Agent");
                }
            } catch (Exception e) {
                log.warn("Could not extract request attributes: {}", e.getMessage());
            }

            // Fetch location if IP is available
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

            java.time.ZoneId istZone = java.time.ZoneId.of("Asia/Kolkata");
            LocalDateTime now = java.time.ZonedDateTime.now(istZone).toLocalDateTime();

            // Create new session
            UserSession session = new UserSession();
            session.setUserId(userId);
            session.setMailAccountId(mailAccountId);
            session.setEncryptedPassword(encryptedPassword);
            session.setJwtToken(jwtToken);
            session.setIpAddress(ipAddress);
            session.setDeviceName(userAgent);
            session.setLocation(location);
            session.setCreatedAt(now);
            session.setLastActiveAt(now);
            session.setExpiresAt(now.plusDays(30)); // 30 days
            
            session = sessionRepository.save(session);
            log.info("Created session for user: {} (mail_account: {})", userId, mailAccountId);
            
            return session;
            
        } catch (Exception e) {
            log.error("Failed to create session: {}", e.getMessage(), e);
            throw new MailException("Failed to create session");
        }
    }
    
    /**
     * Get password from session by JWT token
     * Each user's password is isolated by their token
     */
    public String getPasswordFromSession(String jwtToken) {
        try {
            java.util.Optional<UserSession> sessionOpt = sessionRepository.findByJwtToken(jwtToken);
            if (sessionOpt.isPresent()) {
                UserSession session = sessionOpt.get();
                if (session.getExpiresAt().isBefore(LocalDateTime.now())) {
                    log.warn("Session expired for user: {}", session.getUserId());
                    sessionRepository.delete(session);
                } else {
                    return decrypt(session.getEncryptedPassword());
                }
            }

            // Fallback 1: Extract email from JWT and find active session or persistent password for user
            String email = null;
            try {
                if (jwtUtil != null) {
                    email = jwtUtil.extractEmail(jwtToken);
                }
            } catch (Exception ex) {
                log.warn("Could not extract email from token in SessionService: {}", ex.getMessage());
            }

            if (email != null && userRepository != null) {
                var userOpt = userRepository.findByEmail(email);
                if (userOpt.isEmpty()) {
                    userOpt = userRepository.findByUsername(email);
                }
                if (userOpt.isPresent()) {
                    com.btctech.mailapp.entity.User user = userOpt.get();
                    
                    // Check if there is another valid session for this user
                    String recoveredPassword = getPasswordByUserId(user.getId());
                    
                    // Fallback 2: Check MailAccount persistent encrypted password
                    if (recoveredPassword == null && mailAccountRepository != null) {
                        var accounts = mailAccountRepository.findByUserId(user.getId());
                        for (com.btctech.mailapp.entity.MailAccount acc : accounts) {
                            if (acc.getEncryptedPassword() != null) {
                                try {
                                    recoveredPassword = decrypt(acc.getEncryptedPassword());
                                    if (recoveredPassword != null) {
                                        break;
                                    }
                                } catch (Exception e) {
                                    // ignore decryption error
                                }
                            }
                        }
                    }

                    if (recoveredPassword != null) {
                        // Auto-bind this new jwtToken to a new UserSession so future lookups are instant
                        try {
                            var primaryAcc = mailAccountRepository.findByUserIdAndIsPrimary(user.getId(), true)
                                    .orElse(mailAccountRepository.findByUserId(user.getId()).stream().findFirst().orElse(null));
                            if (primaryAcc != null) {
                                createSession(user.getId(), primaryAcc.getId(), recoveredPassword, jwtToken);
                                log.info("✓ Auto-bound and recovered session for token of user: {}", email);
                            }
                        } catch (Exception e) {
                            log.warn("Could not auto-bind session: {}", e.getMessage());
                        }
                        return recoveredPassword;
                    }
                }
            }

            throw new MailException("Session not found. Please login again.");
        } catch (MailException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to get password from session: {}", e.getMessage(), e);
            throw new MailException("Failed to retrieve session. Please login again.");
        }
    }

    /**
     * Get password for a user ID (for background tasks)
     */
    public String getPasswordByUserId(Long userId) {
        try {
            java.util.List<UserSession> sessions = sessionRepository.findByUserId(userId);
            if (sessions.isEmpty()) {
                log.warn("No active session found for user: {}", userId);
                return null;
            }
            
            // Get the most recent non-expired session
            UserSession session = sessions.stream()
                .filter(s -> s.getExpiresAt().isAfter(LocalDateTime.now()))
                .findFirst()
                .orElse(null);
                
            if (session == null) return null;
            
            return decrypt(session.getEncryptedPassword());
        } catch (Exception e) {
            log.error("Failed to retrieve password for background task: {}", e.getMessage());
            return null;
        }
    }

    
    /**
     * Cleanup expired sessions
     */
    @Transactional
    public void cleanupExpiredSessions() {
        int deleted = sessionRepository.deleteByExpiresAtBefore(LocalDateTime.now());
        if (deleted > 0) {
            log.info("Cleaned up {} expired sessions", deleted);
        }
    }
    
    /**
     * Delete session by token (logout)
     */
    @Transactional
    public void deleteSession(String jwtToken) {
        sessionRepository.findByJwtToken(jwtToken).ifPresent(session -> {
            sessionRepository.delete(session);
            log.info("Deleted session for user: {}", session.getUserId());
        });
    }

    /**
     * Delete all sessions by user ID
     */
    @Transactional
    public void deleteSessionsByUserId(Long userId) {
        sessionRepository.deleteByUserId(userId);
        log.info("Deleted all sessions for user: {}", userId);
    }
    
    /**
     * Encrypt text using AES
     */
    public String encrypt(String plainText) throws Exception {
        SecretKeySpec key = new SecretKeySpec(encryptionKey.getBytes(), "AES");
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(plainText.getBytes());
        return Base64.getEncoder().encodeToString(encrypted);
    }
    
    /**
     * Decrypt text using AES
     */
    public String decrypt(String encryptedText) throws Exception {
        SecretKeySpec key = new SecretKeySpec(encryptionKey.getBytes(), "AES");
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.DECRYPT_MODE, key);
        byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(encryptedText));
        return new String(decrypted);
    }
}