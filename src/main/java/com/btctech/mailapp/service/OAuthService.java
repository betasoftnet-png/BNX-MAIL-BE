package com.btctech.mailapp.service;

import com.btctech.mailapp.config.JwtUtil;
import com.btctech.mailapp.entity.ClientApp;
import com.btctech.mailapp.repository.ClientAppRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class OAuthService {

    private final ClientAppRepository clientAppRepository;
    private final com.btctech.mailapp.repository.UserRepository userRepository;
    private final com.btctech.mailapp.repository.ExternalAppSessionRepository externalAppSessionRepository;
    private final JwtUtil jwtUtil;

    // In-memory store for authorization codes (code -> data)
    // In production, use Redis or Database with expiration
    private final Map<String, AuthCodeData> codeStore = new ConcurrentHashMap<>();

    public String generateAuthorizationCode(String clientId, String redirectUri, String email) {
        ClientApp client = clientAppRepository.findByClientId(clientId)
                .orElseThrow(() -> new RuntimeException("Invalid client_id"));

        String configuredUri = client.getRedirectUri();
        boolean match = configuredUri.equals(redirectUri);
        if (!match && configuredUri.contains(",")) {
            for (String uri : configuredUri.split(",")) {
                if (uri.trim().equals(redirectUri)) {
                    match = true;
                    break;
                }
            }
        }

        if (!match) {
            throw new RuntimeException("Invalid redirect_uri");
        }

        String code = UUID.randomUUID().toString();
        codeStore.put(code, new AuthCodeData(clientId, email, System.currentTimeMillis() + 300000)); // 5 mins expiry
        return code;
    }

    @org.springframework.transaction.annotation.Transactional
    public String exchangeCodeForToken(String code, String clientId, String clientSecret, String ipAddress, String userAgent) {
        AuthCodeData data = codeStore.get(code);
        if (data == null) {
            throw new RuntimeException("Invalid or expired authorization code");
        }

        if (System.currentTimeMillis() > data.expiry) {
            codeStore.remove(code);
            throw new RuntimeException("Authorization code expired");
        }

        ClientApp client = clientAppRepository.findByClientId(clientId)
                .orElseThrow(() -> new RuntimeException("Invalid client_id"));

        if (!client.getClientSecret().equals(clientSecret)) {
            throw new RuntimeException("Invalid client_secret");
        }

        if (!data.clientId.equals(clientId)) {
            throw new RuntimeException("Code was not issued to this client");
        }

        // 1. Get User
        com.btctech.mailapp.entity.User user = userRepository.findByEmail(data.email)
                .orElseGet(() -> userRepository.findByUsername(data.email)
                        .orElseThrow(() -> new RuntimeException("User not found")));

        // 2. Record SSO Session
        String location = null;
        String lat = null;
        String lon = null;
        try {
            if (ipAddress != null && !ipAddress.equals("127.0.0.1") && !ipAddress.equals("0:0:0:0:0:0:0:1") && !ipAddress.startsWith("192.168.")) {
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
                    lat = lines.get(7);
                    lon = lines.get(8);
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to fetch location for IP: " + ipAddress);
        }

        com.btctech.mailapp.entity.ExternalAppSession session = com.btctech.mailapp.entity.ExternalAppSession.builder()
                .user(user)
                .clientApp(client)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .location(location)
                .latitude(lat)
                .longitude(lon)
                .build();
        externalAppSessionRepository.save(session);

        // 3. Remove code after single use
        codeStore.remove(code);

        // 4. Generate a new long-lived token for the client app with the app name included in the token payload
        Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("app_name", client.getAppName());
        
        if (Boolean.TRUE.equals(user.getIsSubId()) && user.getParent() != null) {
            claims.put("is_sub_id", true);
            String parentEmail = user.getParent().getEmail();
            if (parentEmail == null || !parentEmail.contains("@")) {
                parentEmail = user.getParent().getUsername() + "@bnxmail.com";
            }
            claims.put("parent_account", parentEmail);
            
            if (user.getPermissions() != null && !user.getPermissions().isEmpty()) {
                claims.put("permissions", user.getPermissions());
            }

            if (user.getBranchId() != null) {
                claims.put("branch_id", user.getBranchId());
                claims.put("branchId", user.getBranchId());
            }
            if (user.getBranchName() != null) {
                claims.put("branch_name", user.getBranchName());
                claims.put("branchName", user.getBranchName());
            }
        }
        
        return jwtUtil.generateTokenWithClaims(claims, data.email);
    }

    private static record AuthCodeData(String clientId, String email, long expiry) {}
}
