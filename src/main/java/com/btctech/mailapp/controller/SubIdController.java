package com.btctech.mailapp.controller;

import com.btctech.mailapp.dto.ApiResponse;
import com.btctech.mailapp.dto.CreateEmailRequest;
import com.btctech.mailapp.dto.SubIdRequest;
import com.btctech.mailapp.entity.AccountType;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.repository.UserRepository;
import com.btctech.mailapp.config.JwtUtil;
import com.btctech.mailapp.service.MailboxService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/subid")
@RequiredArgsConstructor
@Slf4j
public class SubIdController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final MailboxService mailboxService;

    @PostMapping("/create")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createSubId(
            @Valid @RequestBody SubIdRequest request,
            @RequestHeader("Authorization") String authHeader) {

        try {
            String token = authHeader.substring(7);
            String identifier = jwtUtil.extractEmail(token);
            User parent = userRepository.findByEmail(identifier)
                    .orElseGet(() -> userRepository.findByUsername(identifier)
                    .orElseThrow(() -> new RuntimeException("Parent user not found")));

            String parentEmail = parent.getEmail();
            if (parentEmail == null || !parentEmail.contains("@")) {
                parentEmail = parent.getUsername() + "@bnxmail.com";
            }
            String[] emailParts = parentEmail.split("@");
            String localPart = emailParts[0];
            String domain = emailParts[1];

            String subUsername = request.getPrefix().toLowerCase() + "." + localPart;

            if (userRepository.existsByUsername(subUsername)) {
                return ResponseEntity.badRequest().body(ApiResponse.error("Sub-ID already exists"));
            }

            User subId = new User();
            subId.setUsername(subUsername);
            subId.setPassword(passwordEncoder.encode(request.getPassword()));
            subId.setFirstName(request.getFirstName());
            subId.setLastName(request.getLastName());
            subId.setParent(parent);
            subId.setIsSubId(true);
            
            if ("BUSINESS".equalsIgnoreCase(request.getAccountType())) {
                subId.setAccountType(AccountType.BUSINESS);
            } else {
                subId.setAccountType(AccountType.PUBLIC);
            }

            subId.setRole("SUB_USER");
            subId.setActive(true);
            subId.setApproved(true);
            
            if (request.getPermissions() != null) {
                subId.setPermissions(new java.util.ArrayList<>(request.getPermissions()));
            }
            
            userRepository.save(subId);

            // Create Mailbox for Sub-ID
            CreateEmailRequest mailReq = new CreateEmailRequest();
            mailReq.setEmailName(subUsername);
            mailReq.setPassword(request.getPassword());
            mailboxService.createCustomEmail(subId, mailReq, request.getPassword(), domain);

            return ResponseEntity.ok(ApiResponse.success(
                    Map.of("subUsername", subUsername),
                    "Sub-ID created successfully"
            ));
        } catch (Exception e) {
            log.error("Failed to create Sub-ID", e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Failed to create Sub-ID: " + e.getMessage()));
        }
    }

    @GetMapping("/list")
    public ResponseEntity<ApiResponse<List<User>>> listSubIds(
            @RequestHeader("Authorization") String authHeader) {
        
        try {
            String token = authHeader.substring(7);
            String identifier = jwtUtil.extractEmail(token);
            User parent = userRepository.findByEmail(identifier)
                    .orElseGet(() -> userRepository.findByUsername(identifier)
                    .orElseThrow(() -> new RuntimeException("Parent user not found")));

            List<User> subIds = userRepository.findByParent(parent);
            // Redact passwords before sending
            subIds.forEach(u -> u.setPassword(null));

            return ResponseEntity.ok(ApiResponse.success(subIds, "Fetched Sub-IDs"));
        } catch (Exception e) {
            log.error("Failed to list Sub-IDs", e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Failed to list Sub-IDs: " + e.getMessage()));
        }
    }

    @PutMapping("/{subId}/permissions")
    public ResponseEntity<ApiResponse<Void>> updatePermissions(
            @PathVariable Long subId,
            @RequestBody List<Integer> permissions,
            @RequestHeader("Authorization") String authHeader) {
        try {
            String token = authHeader.substring(7);
            String identifier = jwtUtil.extractEmail(token);
            User parent = userRepository.findByEmail(identifier)
                    .orElseGet(() -> userRepository.findByUsername(identifier)
                    .orElseThrow(() -> new RuntimeException("Parent user not found")));

            User subUser = userRepository.findById(subId)
                    .orElseThrow(() -> new RuntimeException("Sub-ID not found"));

            if (subUser.getParent() == null || !subUser.getParent().getId().equals(parent.getId())) {
                return ResponseEntity.status(403).body(ApiResponse.error("Unauthorized to modify this Sub-ID"));
            }

            subUser.setPermissions(permissions != null ? new java.util.ArrayList<>(permissions) : new java.util.ArrayList<>());
            userRepository.save(subUser);

            return ResponseEntity.ok(ApiResponse.success(null, "Permissions updated successfully"));
        } catch (Exception e) {
            log.error("Failed to update permissions", e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Failed to update permissions: " + e.getMessage()));
        }
    }
}
