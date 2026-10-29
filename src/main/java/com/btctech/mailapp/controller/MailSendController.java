package com.btctech.mailapp.controller;

import com.btctech.mailapp.dto.ApiResponse;
import com.btctech.mailapp.dto.SendMailRequest;
import com.btctech.mailapp.dto.BulkMailRequest;
import com.btctech.mailapp.exception.MailException;
import com.btctech.mailapp.entity.MailAccount;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.service.MailSendService;
import com.btctech.mailapp.service.MailboxService;
import com.btctech.mailapp.service.SessionService;
import com.btctech.mailapp.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/mail")
@RequiredArgsConstructor
public class MailSendController {

    private final MailSendService mailSendService;
    private final SessionService sessionService;
    private final MailboxService mailboxService;
    private final UserService userService;
    private final com.btctech.mailapp.repository.ScheduledEmailRepository scheduledEmailRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * Send email - NO PASSWORD NEEDED in request!
     * Authorization header provides context to retrieve password from session.
     */
    @PostMapping("/send")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sendMail(
            @Valid @RequestBody SendMailRequest request,
            @RequestHeader("Authorization") String authHeader,
            Authentication authentication) {

        try {
            // Get email from authentication context
            String fromEmail = authentication.getName();
            log.info("Send mail request from {} to {}", fromEmail, request.getTo());

            // Extract JWT token
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401)
                        .body(ApiResponse.error("Missing or invalid Authorization header"));
            }
            String token = authHeader.substring(7);

            // Get password from backend session (Security: never expose password to client)
            String password = sessionService.getPasswordFromSession(token);

            if (password == null || password.isEmpty()) {
                log.error("Password not found in session for {}", fromEmail);
                return ResponseEntity.status(401)
                        .body(ApiResponse.error("Session expired or invalid. Please login again."));
            }

            // Verify user has this email account active
            MailAccount mailAccount = mailboxService.getMailAccountByEmail(fromEmail);
            if (mailAccount == null || !mailAccount.getActive()) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("Email account not found or disabled"));
            }

            // Execute send
            String sentUid = mailSendService.sendMail(fromEmail, password, request);

            boolean isStarred = Boolean.TRUE.equals(request.getIsStarred()) || Boolean.TRUE.equals(request.getStarred());

            // Response payload
            Map<String, Object> data = new HashMap<>();
            data.put("from", fromEmail);
            data.put("to", request.getTo());
            data.put("subject", request.getSubject());
            data.put("sentAt", System.currentTimeMillis());
            if (sentUid != null) {
                data.put("uid", sentUid);
                data.put("folderName", "Sent");
            }
            data.put("starred", isStarred);
            data.put("isStarred", isStarred);

            log.info("✓ Email sent successfully from {} to {} (sentUid={}, isStarred={})", fromEmail, request.getTo(), sentUid, isStarred);

            return ResponseEntity.ok(
                    ApiResponse.success(data, "Email sent successfully"));

        } catch (MailException e) {
            log.error("Mail error: {}", e.getMessage());
            if (e.getMessage().contains("Session")) {
                return ResponseEntity.status(401).body(ApiResponse.error(e.getMessage()));
            }
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            log.error("Error sending email: {}", e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Failed to send email: " + e.getMessage()));
        }
    }

    /**
     * Public email send endpoint for external services (like job applicant confirmation).
     * Protected by a backend-to-backend API Token header "X-Public-Mail-Token".
     */
    @PostMapping("/public/send")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sendPublicMail(
            @Valid @RequestBody SendMailRequest request,
            @RequestHeader(value = "X-Public-Mail-Token", required = false) String apiToken) {

        log.info("Public send request received to: {}, isHtml: {}", request.getTo(), request.getIsHtml());
        try {
            // Verify public token
            String expectedToken = "secure-beta-to-bnx-secret-2026";
            if (apiToken == null || !apiToken.equals(expectedToken)) {
                log.warn("✗ Unauthorized public send request: Invalid or missing X-Public-Mail-Token");
                return ResponseEntity.status(401)
                        .body(ApiResponse.error("Unauthorized: Invalid API Token"));
            }

            // Public sending is on behalf of beta@bnxmail.com
            String fromEmail = "beta@bnxmail.com";

            // Attempt to retrieve password for IMAP Sent archiving (optional)
            String password = null;
            try {
                MailAccount mailAccount = mailboxService.getMailAccountByEmail(fromEmail);
                if (mailAccount != null && mailAccount.getEncryptedPassword() != null) {
                    password = sessionService.decrypt(mailAccount.getEncryptedPassword());
                    log.info("✓ Decrypted password for IMAP Sent archival for {}", fromEmail);
                }
            } catch (Exception e) {
                log.warn("Could not retrieve password for {} (archival copy will be skipped): {}", fromEmail, e.getMessage());
            }

            // Send mail (MailSendService handles sending with null password safely)
            mailSendService.sendMail(fromEmail, password, request);

            // Response payload
            Map<String, Object> data = new HashMap<>();
            data.put("from", fromEmail);
            data.put("to", request.getTo());
            data.put("subject", request.getSubject());
            data.put("sentAt", System.currentTimeMillis());

            log.info("✓ Public email sent successfully from {} to {}", fromEmail, request.getTo());

            return ResponseEntity.ok(ApiResponse.success(data, "Email sent successfully"));

        } catch (Exception e) {
            log.error("Failed to send public email: {}", e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Failed to send public email: " + e.getMessage()));
        }
    }

    /**
     * Bulk send endpoint for marketing/offers.
     * Processes asynchronously.
     */
    @PostMapping("/bulk-send")
    public ResponseEntity<ApiResponse<Map<String, Object>>> bulkSend(
            @Valid @RequestBody BulkMailRequest request,
            @RequestHeader("Authorization") String authHeader,
            Authentication authentication) {

        try {
            String fromEmail = authentication.getName();
            log.info("Bulk send request from {} for {} recipients", fromEmail, request.getRecipients().size());

            // 1. Get the user and their mail account
            User user = userService.getUserByEmailOrUsername(fromEmail);
            
            // 2. Get password from ANY active session for this user
            String password = sessionService.getPasswordByUserId(user.getId());

            // 3. FALLBACK: If no session, try the persistent encrypted password
            if (password == null) {
                log.info("No session found for {}, trying persistent encrypted password", fromEmail);
                MailAccount mailAccount = mailboxService.getMailAccountByEmail(fromEmail);
                if (mailAccount.getEncryptedPassword() != null) {
                    try {
                        password = sessionService.decrypt(mailAccount.getEncryptedPassword());
                        log.info("✓ Recovered password from persistent storage for: {}", fromEmail);
                    } catch (Exception e) {
                        log.error("Failed to decrypt persistent password: {}", e.getMessage());
                    }
                }
            }

            if (password == null) {
                log.warn("No password source found for user: {}", fromEmail);
                return ResponseEntity.status(401).body(ApiResponse.error("Authentication required. Please login to B2Auth to re-sync your session."));
            }

            // Execute bulk send asynchronously
            mailSendService.sendBulkMail(fromEmail, password, request);

            Map<String, Object> data = new HashMap<>();
            data.put("status", "PROCESSING");
            data.put("recipientCount", request.getRecipients().size());

            return ResponseEntity.ok(ApiResponse.success(data, "Bulk email processing started"));

        } catch (Exception e) {
            log.error("Bulk send failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.error("Failed: " + e.getMessage()));
        }
    }

    @PostMapping("/schedule")
    public ResponseEntity<ApiResponse<Map<String, Object>>> scheduleMail(
            @Valid @RequestBody SendMailRequest request,
            @RequestParam("sendAt") String sendAt,
            Authentication authentication) {
        try {
            String fromEmail = authentication.getName();
            log.info("Schedule mail request from {} to {} at {}", fromEmail, request.getTo(), sendAt);

            // Parse ISO date string to LocalDateTime
            java.time.ZonedDateTime zdt = java.time.ZonedDateTime.parse(sendAt);
            java.time.LocalDateTime scheduledTime = zdt.toLocalDateTime();

            if (scheduledTime.isBefore(java.time.LocalDateTime.now())) {
                return ResponseEntity.badRequest().body(ApiResponse.error("Scheduled time must be in the future"));
            }

            String attachmentsJson = null;
            if (request.getAttachments() != null && !request.getAttachments().isEmpty()) {
                attachmentsJson = objectMapper.writeValueAsString(request.getAttachments());
            }

            com.btctech.mailapp.entity.ScheduledEmail scheduledEmail = com.btctech.mailapp.entity.ScheduledEmail.builder()
                    .userEmail(fromEmail)
                    .toRecipient(request.getTo())
                    .cc(request.getCc())
                    .bcc(request.getBcc())
                    .subject(request.getSubject())
                    .body(request.getBody())
                    .fromName(request.getFromName())
                    .isHtml(request.getIsHtml())
                    .attachmentsJson(attachmentsJson)
                    .scheduledAt(scheduledTime)
                    .processed(false)
                    .createdAt(java.time.LocalDateTime.now())
                    .build();

            scheduledEmailRepository.save(scheduledEmail);

            Map<String, Object> data = new HashMap<>();
            data.put("id", scheduledEmail.getId());
            data.put("scheduledAt", sendAt);

            return ResponseEntity.ok(ApiResponse.success(data, "Email scheduled successfully"));
        } catch (Exception e) {
            log.error("Failed to schedule email: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Failed to schedule email: " + e.getMessage()));
        }
    }

    /**
     * Public email schedule endpoint for external services (like Calendar App Reminders).
     * Protected by a backend-to-backend API Token header "X-Public-Mail-Token".
     * Sender is ALWAYS calendar@bnxmail.com
     */
    @PostMapping("/public/schedule")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publicScheduleMail(
            @Valid @RequestBody SendMailRequest request,
            @RequestParam("sendAt") String sendAt,
            @RequestHeader(value = "X-Public-Mail-Token", required = false) String apiToken) {

        log.info("Public schedule mail request to {} at {}", request.getTo(), sendAt);
        try {
            // Verify public token
            String expectedToken = "secure-beta-to-bnx-secret-2026";
            if (apiToken == null || !apiToken.equals(expectedToken)) {
                log.warn("✗ Unauthorized public schedule request: Invalid or missing X-Public-Mail-Token");
                return ResponseEntity.status(401)
                        .body(ApiResponse.error("Unauthorized: Invalid API Token"));
            }

            // Public schedule is ALWAYS on behalf of calendar@bnxmail.com
            String fromEmail = "calendar@bnxmail.com";

            // Parse ISO date string to LocalDateTime
            java.time.ZonedDateTime zdt = java.time.ZonedDateTime.parse(sendAt);
            java.time.LocalDateTime scheduledTime = zdt.toLocalDateTime();

            if (scheduledTime.isBefore(java.time.LocalDateTime.now())) {
                return ResponseEntity.badRequest().body(ApiResponse.error("Scheduled time must be in the future"));
            }

            String attachmentsJson = null;
            if (request.getAttachments() != null && !request.getAttachments().isEmpty()) {
                attachmentsJson = objectMapper.writeValueAsString(request.getAttachments());
            }

            com.btctech.mailapp.entity.ScheduledEmail scheduledEmail = com.btctech.mailapp.entity.ScheduledEmail.builder()
                    .userEmail(fromEmail)
                    .toRecipient(request.getTo())
                    .cc(request.getCc())
                    .bcc(request.getBcc())
                    .subject(request.getSubject())
                    .body(request.getBody())
                    .fromName(request.getFromName() != null ? request.getFromName() : "BNX Calendar")
                    .isHtml(request.getIsHtml())
                    .attachmentsJson(attachmentsJson)
                    .scheduledAt(scheduledTime)
                    .processed(false)
                    .createdAt(java.time.LocalDateTime.now())
                    .build();

            scheduledEmailRepository.save(scheduledEmail);

            Map<String, Object> data = new HashMap<>();
            data.put("id", scheduledEmail.getId());
            data.put("scheduledAt", sendAt);
            data.put("from", fromEmail);

            return ResponseEntity.ok(ApiResponse.success(data, "Public email scheduled successfully"));
        } catch (Exception e) {
            log.error("Failed to schedule public email: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Failed to schedule public email: " + e.getMessage()));
        }
    }

    /**
     * Public email schedule cancel endpoint for external services (like Calendar App Reminders).
     * Protected by a backend-to-backend API Token header "X-Public-Mail-Token".
     */
    @DeleteMapping("/public/schedule/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cancelPublicScheduledMail(
            @PathVariable Long id,
            @RequestHeader(value = "X-Public-Mail-Token", required = false) String apiToken) {
        
        try {
            // Verify public token
            String expectedToken = "secure-beta-to-bnx-secret-2026";
            if (apiToken == null || !apiToken.equals(expectedToken)) {
                log.warn("✗ Unauthorized public schedule cancel request: Invalid or missing X-Public-Mail-Token");
                return ResponseEntity.status(401)
                        .body(ApiResponse.error("Unauthorized: Invalid API Token"));
            }

            String fromEmail = "calendar@bnxmail.com";
            com.btctech.mailapp.entity.ScheduledEmail scheduledEmail = scheduledEmailRepository.findByIdAndUserEmail(id, fromEmail).orElse(null);

            if (scheduledEmail == null) {
                return ResponseEntity.badRequest().body(ApiResponse.error("Scheduled email not found"));
            }

            scheduledEmailRepository.delete(scheduledEmail);

            Map<String, Object> data = new HashMap<>();
            data.put("id", id);

            return ResponseEntity.ok(ApiResponse.success(data, "Public scheduled email cancelled successfully"));
        } catch (Exception e) {
            log.error("Error cancelling public scheduled email: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Failed to cancel public scheduled email: " + e.getMessage()));
        }
    }

    @GetMapping("/scheduled")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getScheduledEmails(Authentication authentication) {
        try {
            String fromEmail = authentication.getName();
            java.util.List<com.btctech.mailapp.entity.ScheduledEmail> list = scheduledEmailRepository.findByUserEmailAndProcessedFalse(fromEmail);

            // Map to frontend expected email format
            java.util.List<Map<String, Object>> emails = new java.util.ArrayList<>();
            for (com.btctech.mailapp.entity.ScheduledEmail s : list) {
                Map<String, Object> map = new HashMap<>();
                map.put("uid", s.getId().toString()); // EmailList expects uid as string or number
                map.put("id", s.getId());
                map.put("from", s.getUserEmail());
                map.put("to", s.getToRecipient());
                map.put("cc", s.getCc());
                map.put("bcc", s.getBcc());
                map.put("subject", s.getSubject());
                map.put("body", s.getBody());
                map.put("isHtml", s.getIsHtml());
                map.put("isRead", true);
                map.put("starred", false);
                map.put("folderName", "scheduled");
                // Pass scheduled time as receivedDate so it displays beautifully in EmailList
                map.put("receivedDate", java.time.ZonedDateTime.of(s.getScheduledAt(), java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
                map.put("scheduledAt", s.getScheduledAt().toString());
                emails.add(map);
            }

            Map<String, Object> data = new HashMap<>();
            data.put("emails", emails);
            data.put("total", emails.size());

            return ResponseEntity.ok(ApiResponse.success(data, "Scheduled emails retrieved successfully"));
        } catch (Exception e) {
            log.error("Error retrieving scheduled emails: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Failed to retrieve scheduled emails: " + e.getMessage()));
        }
    }

    @DeleteMapping("/scheduled/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cancelScheduledEmail(
            @PathVariable Long id,
            Authentication authentication) {
        try {
            String fromEmail = authentication.getName();
            com.btctech.mailapp.entity.ScheduledEmail scheduledEmail = scheduledEmailRepository.findByIdAndUserEmail(id, fromEmail).orElse(null);

            if (scheduledEmail == null) {
                return ResponseEntity.badRequest().body(ApiResponse.error("Scheduled email not found"));
            }

            scheduledEmailRepository.delete(scheduledEmail);

            Map<String, Object> data = new HashMap<>();
            data.put("id", id);

            return ResponseEntity.ok(ApiResponse.success(data, "Scheduled email cancelled successfully"));
        } catch (Exception e) {
            log.error("Error cancelling scheduled email: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Failed to cancel scheduled email: " + e.getMessage()));
        }
    }
}