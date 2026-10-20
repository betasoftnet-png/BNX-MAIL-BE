package com.btctech.mailapp.config;

import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.service.UserService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {
    
    private final JwtUtil jwtUtil;
    private final UserService userService;
    private final com.btctech.mailapp.repository.SystemSettingRepository systemSettingRepository;
    private final com.btctech.mailapp.repository.RefreshTokenRepository refreshTokenRepository;
    private final com.btctech.mailapp.repository.ExternalAppSessionRepository externalAppSessionRepository;
    
    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {
        
        // Skip filter for public endpoints
        String path = request.getRequestURI();
        if (path.equals("/") || 
            path.equals("/index.html") ||
            path.startsWith("/api/auth/register") || 
            path.startsWith("/api/auth/login") ||
            path.startsWith("/api/auth/system-status") ||
            path.startsWith("/api/auth/verify-business") ||
            path.equals("/error")) {
            filterChain.doFilter(request, response);
            return;
        }
        
        final String authHeader = request.getHeader("Authorization");
        
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("Missing or invalid Authorization header for path: {}", path);
            filterChain.doFilter(request, response);
            return;
        }
        
        try {
            String jwt = authHeader.substring(7);
            String jwtSubject = jwtUtil.extractEmail(jwt);
            
            log.debug("Processing JWT for subject: {}", jwtSubject);
            
            if (jwtSubject != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                
                User user = null;
                String principal = null;
                
                if (jwtSubject.startsWith("temp_")) {
                    // Temporary token
                    String username = jwtSubject.substring(5);
                    user = userService.getUserByUsername(username);
                    principal = username;
                    
                    log.debug("Temp token - username: {}", username);
                    
                } else {
                    // Handle regular tokens (emails) and other cases
                    user = userService.getUserByEmailOrUsername(jwtSubject);
                    principal = jwtSubject;
                    
                    log.debug("Validated token for subject: {}", jwtSubject);
                }
                
                if (user != null) {
                    if (Boolean.FALSE.equals(user.getActive())) {
                        log.warn("✗ Token rejected: User {} is suspended", jwtSubject);
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.setContentType("application/json");
                        response.getWriter().write("{\"error\": \"Account suspended\"}");
                        return;
                    }
                    
                    if (!"ADMIN".equalsIgnoreCase(user.getRole())) {
                        com.btctech.mailapp.entity.SystemSetting maintenanceSetting = systemSettingRepository.findById("maintenance_mode").orElse(null);
                        if (maintenanceSetting != null && "true".equalsIgnoreCase(maintenanceSetting.getSettingValue())) {
                            log.warn("✗ Token rejected: Platform is in maintenance mode");
                            response.setStatus(503);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"error\": \"maintenance_mode\", \"message\": \"Platform is under maintenance\"}");
                            return;
                        }
                    }

                    io.jsonwebtoken.Claims claims = null;
                    try {
                        claims = jwtUtil.extractAllClaims(jwt);
                    } catch (Exception e) {
                        log.debug("Could not parse all claims: {}", e.getMessage());
                    }

                    Object sessionIdObj = claims != null ? claims.get("session_id") : null;
                    boolean isExternalToken = sessionIdObj != null || (claims != null && "oauth_access_token".equals(claims.get("token_type")));

                    if (sessionIdObj != null) {
                        try {
                            Long extSessionId = Long.valueOf(sessionIdObj.toString());
                            var extSessionOpt = externalAppSessionRepository.findById(extSessionId);
                            if (extSessionOpt.isEmpty() || Boolean.TRUE.equals(extSessionOpt.get().getRevoked())) {
                                log.warn("✗ Token rejected: External session {} is revoked or removed", extSessionId);
                                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                                response.setContentType("application/json");
                                response.getWriter().write("{\"error\": \"session_revoked\", \"message\": \"Your session has been revoked. Please log in again.\"}");
                                return;
                            }
                        } catch (Exception ex) {
                            log.warn("✗ Error verifying external app session ID: {}", ex.getMessage());
                        }
                    }

                    if (jwtUtil.validateToken(jwt, jwtSubject)) {
                        
                        // Check if user has active sessions (if no refresh tokens exist, they were force logged out)
                        // Note: Temporary tokens for 2FA bypass this check as they haven't established a full session yet.
                        // External OAuth tokens are validated against ExternalAppSession, so bypass refreshTokenRepository check.
                        boolean isTempToken = jwtSubject.startsWith("temp_");
                        boolean hasActiveSessions = isExternalToken || !refreshTokenRepository.findAllByUserAndRevokedFalse(user).isEmpty();
                        if (!isTempToken && !hasActiveSessions) {
                            log.warn("✗ Token rejected: User {} has no active sessions (forcefully logged out)", jwtSubject);
                            response.setStatus(401);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"error\": \"Session invalidated\"}");
                            return;
                        }

                        // Map user roles into authorities
                        List<SimpleGrantedAuthority> authorities = Collections.singletonList(
                            new SimpleGrantedAuthority(user.getRole() != null ? user.getRole() : "ROLE_USER")
                        );

                        UsernamePasswordAuthenticationToken authToken = 
                            new UsernamePasswordAuthenticationToken(
                                principal,  // Email or username
                                null,
                                authorities
                            );
                        
                        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authToken);
                        
                        log.debug("✓ Authentication successful for: {} with roles: {}", principal, authorities);
                    } else {
                        log.warn("✗ Token validation failed in JwtUtil for subject: {}. Token might be expired or secret changed.", jwtSubject);
                    }
                } else {
                    log.error("✗ Authentication failed: User not found in database for identifier (subject): {}", jwtSubject);
                }
            }
            
        } catch (JwtException e) {
            log.error("✗ JWT validation error for path {}: {}", path, e.getMessage());
        } catch (Exception e) {
            log.error("✗ Unexpected authentication error for path {}: {}", path, e.getMessage(), e);
        }
        
        filterChain.doFilter(request, response);
    }
}