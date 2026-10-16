package com.btctech.mailapp.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Component
public class JwtUtil {

    @Value("${jwt.secret:secretsecretsecretsecretsecretsecretsecret}")
    private String secret;

    @Value("${jwt.expiration:900000}") // Default 15 mins (900s)
    private Long jwtExpiration;

    @Value("${jwt.refresh.expiration:604800000}") // Default 7 days (604800s)
    private Long refreshExpiration;

    private java.security.Key getSigningKey() {
        byte[] keyBytes = secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return io.jsonwebtoken.security.Keys.hmacShaKeyFor(keyBytes);
    }

    public String extractEmail(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    public Claims extractAllClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private Boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    public String generateTokenWithClaims(Map<String, Object> claims, String username) {
        if (!claims.containsKey("app_name")) {
            claims.put("app_name", "BNX Mail");
        }
        return generateToken(claims, username, jwtExpiration);
    }

    public String generateToken(String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("app_name", "BNX Mail");
        return generateToken(claims, username, jwtExpiration);
    }

    public String generateTokenForUser(com.btctech.mailapp.entity.User user, String identifier) {
        Map<String, Object> claims = new HashMap<>();
        if (Boolean.TRUE.equals(user.getIsSubId()) && user.getParent() != null) {
            claims.put("is_sub_id", true);
            claims.put("parent_account", user.getParent().getUsername());
            if (user.getBranchId() != null) {
                claims.put("branch_id", user.getBranchId());
                claims.put("branchId", user.getBranchId());
            }
            if (user.getBranchName() != null) {
                claims.put("branch_name", user.getBranchName());
                claims.put("branchName", user.getBranchName());
            }
        }
        claims.put("app_name", "BNX Mail");
        return generateToken(claims, identifier, jwtExpiration);
    }

    public String generateRefreshToken(String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("app_name", "BNX Mail");
        return generateToken(claims, username, refreshExpiration);
    }

    private String generateToken(Map<String, Object> claims, String subject, long expirationDuration) {
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + expirationDuration))
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public Long getExpirationSeconds(String token) {
        Date expirationDate = extractExpiration(token);
        long diff = expirationDate.getTime() - System.currentTimeMillis();
        return Math.max(0, diff / 1000);
    }

    public Boolean validateToken(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return (username.equals(userDetails.getUsername()) && !isTokenExpired(token));
    }

    public Boolean validateToken(String token, String username) {
        final String extractedUsername = extractUsername(token);
        return (extractedUsername.equals(username) && !isTokenExpired(token));
    }
}