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
            path.startsWith("/api/auth/refresh") ||
            path.startsWith("/api/auth/forgot-password") ||
            path.startsWith("/api/auth/reset-password") ||
            path.startsWith("/api/auth/system-status") ||
            path.startsWith("/api/auth/verify-business") ||
            path.startsWith("/ws") ||
            path.startsWith("/swagger-ui") ||
            path.startsWith("/v3/api-docs") ||
            path.equals("/error")) {
            filterChain.doFilter(request, response);
            return;
        }
        
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null) {
            authHeader = request.getHeader("authorization");
        }
        
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("[AUTH DIAGNOSTIC] Path: {} | Authorization header: {} | Reason: Header missing or lacks 'Bearer ' prefix",
                    path, authHeader != null ? "Present (invalid prefix)" : "Missing");
            filterChain.doFilter(request, response);
            return;
        }
        
        try {
            String jwt = authHeader.substring(7).trim();
            String jwtSubject = jwtUtil.extractEmail(jwt);
            java.util.Date expiration = null;
            try {
                expiration = jwtUtil.extractExpiration(jwt);
            } catch (Exception ignored) {}

            log.info("[AUTH DIAGNOSTIC] Path: {} | Subject: {} | Expiration: {} | Expired: {}",
                    path, jwtSubject, expiration, expiration != null && expiration.before(new java.util.Date()));
            
            if (jwtSubject != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                
                User user = null;
                String principal = null;
                
                if (jwtSubject.startsWith("temp_")) {
                    // Temporary token
                    String username = jwtSubject.substring(5).trim();
                    user = userService.getUserByUsername(username);
                    principal = username;
                    
                    log.debug("Temp token - username: {}", username);
                    
                } else {
                    // Handle regular tokens (emails) and other cases
                    user = userService.getUserByEmailOrUsername(jwtSubject.trim());
                    principal = jwtSubject.trim();
                    
                    log.debug("Validated token for subject: {}", jwtSubject);
                }
                
                if (user != null) {
                    log.info("[AUTH DIAGNOSTIC] Path: {} | User loaded: {} (ID: {}) | Active: {} | Role: {}",
                            path, user.getUsername(), user.getId(), user.getActive(), user.getRole());

                    if (Boolean.FALSE.equals(user.getActive())) {
                        log.warn("[AUTH DIAGNOSTIC] Path: {} | REJECTED: User {} is suspended", path, jwtSubject);
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.setContentType("application/json");
                        response.getWriter().write("{\"error\": \"Account suspended\"}");
                        return;
                    }
                    
                    if (!"ADMIN".equalsIgnoreCase(user.getRole())) {
                        com.btctech.mailapp.entity.SystemSetting maintenanceSetting = systemSettingRepository.findById("maintenance_mode").orElse(null);
                        if (maintenanceSetting != null && "true".equalsIgnoreCase(maintenanceSetting.getSettingValue())) {
                            log.warn("[AUTH DIAGNOSTIC] Path: {} | REJECTED: Platform is in maintenance mode", path);
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
                                log.warn("[AUTH DIAGNOSTIC] Path: {} | REJECTED: External session {} is revoked or removed", path, extSessionId);
                                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                                response.setContentType("application/json");
                                response.getWriter().write("{\"error\": \"session_revoked\", \"message\": \"Your session has been revoked. Please log in again.\"}");
                                return;
                            }
                        } catch (Exception ex) {
                            log.warn("[AUTH DIAGNOSTIC] Path: {} | Error verifying external app session ID: {}", path, ex.getMessage());
                        }
                    }

                    if (Boolean.TRUE.equals(jwtUtil.validateToken(jwt, jwtSubject))) {
                        
                        // Check if user has active sessions (if no refresh tokens exist, they were force logged out)
                        boolean isTempToken = jwtSubject.startsWith("temp_");
                        List<com.btctech.mailapp.entity.RefreshToken> activeRefreshTokens = refreshTokenRepository.findAllByUserAndRevokedFalse(user);
                        boolean hasActiveSessions = isExternalToken || !activeRefreshTokens.isEmpty();
                        
                        log.info("[AUTH DIAGNOSTIC] Path: {} | Active refresh tokens: {} | isExternalToken: {}",
                                path, activeRefreshTokens.size(), isExternalToken);

                        if (!isTempToken && !hasActiveSessions) {
                            log.warn("[AUTH DIAGNOSTIC] Path: {} | REJECTED: User {} has no active sessions (0 active refresh tokens in DB)", path, jwtSubject);
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
                        
                        log.info("[AUTH DIAGNOSTIC] Path: {} | ✓ Authentication SUCCESS for: {} with roles: {}", path, principal, authorities);
                    } else {
                        log.warn("[AUTH DIAGNOSTIC] Path: {} | REJECTED: Token validation failed in JwtUtil for subject: {}", path, jwtSubject);
                    }
                } else {
                    log.error("[AUTH DIAGNOSTIC] Path: {} | REJECTED: User not found in database for identifier (subject): {}", path, jwtSubject);
                }
            }
            
        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            log.error("[AUTH DIAGNOSTIC] Path: {} | REJECTED: Token EXPIRED at {}: {}", path, e.getClaims() != null ? e.getClaims().getExpiration() : "unknown", e.getMessage());
        } catch (JwtException e) {
            log.error("[AUTH DIAGNOSTIC] Path: {} | REJECTED: JWT error: {}", path, e.getMessage());
        } catch (Exception e) {
            log.error("[AUTH DIAGNOSTIC] Path: {} | REJECTED: Unexpected authentication error: {}", path, e.getMessage(), e);
        }
        
        filterChain.doFilter(request, response);
    }
}