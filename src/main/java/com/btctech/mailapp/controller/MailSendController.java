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
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/mail")
public class MailSendController {

    private final MailSendService mailSendService;
    private final SessionService sessionService;
    private final MailboxService mailboxService;
    private final UserService userService;
    private final com.btctech.mailapp.repository.ScheduledEmailRepository scheduledEmailRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final com.btctech.mailapp.service.PendingEmailService pendingEmailService;
    private final com.btctech.mailapp.service.ReadReceiptService readReceiptService;

    public MailSendController(
            MailSendService mailSendService,
            SessionService sessionService,
            MailboxService mailboxService,
            UserService userService,
            com.btctech.mailapp.repository.ScheduledEmailRepository scheduledEmailRepository,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            com.btctech.mailapp.service.PendingEmailService pendingEmailService,
            com.btctech.mailapp.service.ReadReceiptService readReceiptService) {
        this.mailSendService = mailSendService;
        this.sessionService = sessionService;
        this.mailboxService = mailboxService;
        this.userService = userService;
        this.scheduledEmailRepository = scheduledEmailRepository;
        this.objectMapper = objectMapper;
        this.pendingEmailService = pendingEmailService;
        this.readReceiptService = readReceiptService;
    }

    /**
     * Send email - NO PASSWORD NEEDED in request!
     * Authorization header provides context to retrieve password from session.
     * Supports 5-second delayed email delivery with Undo Send and View Message.
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

            User user = userService.getUserByEmailOrUsername(fromEmail);
            Long userId = user != null ? user.getId() : null;

            // Read Receipt Access Control (Strictly Business Accounts Only)
            if (Boolean.TRUE.equals(request.getRequestReadReceipt())) {
                if (!readReceiptService.isBusinessAccount(fromEmail)) {
                    log.warn("Unauthorized read receipt request by non-business account: {}", fromEmail);
                    return ResponseEntity.status(403)
                            .body(ApiResponse.error("Read receipt feature is only available to Business Accounts"));
                }
                String sendId = request.getSendId();
                if (sendId == null || sendId.trim().isEmpty()) {
                    sendId = java.util.UUID.randomUUID().toString();
                    request.setSendId(sendId);
                }
                com.btctech.mailapp.entity.ReadReceipt receipt = readReceiptService.createReceiptRequest(
                        fromEmail, userId, request.getTo(), request.getSubject(), sendId);
                request.setReadReceiptId(receipt.getReceiptId());
            }

            int delaySeconds = request.getUndoDelay() != null ? request.getUndoDelay() : 5;
            boolean isStarred = Boolean.TRUE.equals(request.getIsStarred()) || Boolean.TRUE.equals(request.getStarred());

            if (delaySeconds > 0) {
                // Delayed delivery with 5-second Undo Send window
                try {
                    com.btctech.mailapp.entity.PendingEmail pending = pendingEmailService.queuePendingEmail(
                            fromEmail, userId, mailAccount.getId(), password, request, delaySeconds);

                    long expiresAt = java.time.ZonedDateTime.of(pending.getUndoDeadline(), java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();

                    Map<String, Object> data = new HashMap<>();
                    data.put("sendId", pending.getSendId());
                    data.put("status", pending.getStatus());
                    data.put("delaySeconds", delaySeconds);
                    data.put("undoDeadline", pending.getUndoDeadline().toString());
                    data.put("expiresAt", expiresAt);
                    data.put("from", fromEmail);
                    data.put("to", request.getTo());
                    data.put("cc", request.getCc());
                    data.put("bcc", request.getBcc());
                    data.put("subject", request.getSubject());
                    data.put("body", request.getBody());
                    data.put("isHtml", request.getIsHtml());
                    data.put("sentAt", System.currentTimeMillis());
                    data.put("starred", isStarred);
                    data.put("isStarred", isStarred);
                    if (request.getReadReceiptId() != null) {
                        data.put("readReceiptId", request.getReadReceiptId());
                        data.put("readReceiptRequested", true);
                    }

                    log.info("✓ Email queued with {}s undo window (sendId={}) from {} to {}", delaySeconds, pending.getSendId(), fromEmail, request.getTo());
                    return ResponseEntity.ok(ApiResponse.success(data, "Message sent"));
                } catch (Exception queueEx) {
                    log.warn("Failed to queue pending email with undo window (falling back to immediate send): {}", queueEx.getMessage());
                }
            }

            // Immediate send fallback if undoDelay is explicitly 0 or if queuePendingEmail threw an error
            String sentUid = mailSendService.sendMail(fromEmail, password, request);

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
            if (request.getReadReceiptId() != null) {
                data.put("readReceiptId", request.getReadReceiptId());
                data.put("readReceiptRequested", true);
            }

            log.info("✓ Email sent immediately from {} to {} (sentUid={}, isStarred={})", fromEmail, request.getTo(), sentUid, isStarred);

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
     * Undo Send within the 5-second cancellation window.
     * Cancels pending delivery and returns original email details for Compose restoration.
     */
    @PostMapping({"/undo-send/{sendId}", "/undo/{sendId}"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> undoSend(
            @PathVariable String sendId,
            Authentication authentication) {
        try {
            String userEmail = authentication.getName();
            com.btctech.mailapp.entity.PendingEmail cancelled = pendingEmailService.cancelPendingEmail(sendId, userEmail);

            Map<String, Object> data = new HashMap<>();
            data.put("sendId", cancelled.getSendId());
            data.put("status", cancelled.getStatus());
            data.put("to", cancelled.getToRecipient());
            data.put("cc", cancelled.getCc());
            data.put("bcc", cancelled.getBcc());
            data.put("subject", cancelled.getSubject());
            data.put("body", cancelled.getBody());
            data.put("fromName", cancelled.getFromName());
            data.put("isHtml", cancelled.getIsHtml());
            data.put("draftUid", cancelled.getDraftUid());
            data.put("draftId", cancelled.getDraftId());
            data.put("isStarred", cancelled.getIsStarred());
            data.put("starred", cancelled.getIsStarred());
            data.put("attachments", pendingEmailService.parseAttachments(cancelled.getAttachmentsJson()));

            log.info("✓ Undo Send succeeded for sendId: {} by user: {}", sendId, userEmail);
            return ResponseEntity.ok(ApiResponse.success(data, "Sending cancelled successfully"));
        } catch (MailException e) {
            log.warn("Undo send rejected: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            log.error("Undo send error: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Failed to undo email: " + e.getMessage()));
        }
    }

    /**
     * View pending outgoing message preview and delivery countdown during the 5-second window.
     */
    @GetMapping("/pending/{sendId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPendingEmail(
            @PathVariable String sendId,
            Authentication authentication) {
        try {
            if (authentication == null || authentication.getName() == null) {
                return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
            }
            String userEmail = authentication.getName();
            com.btctech.mailapp.entity.PendingEmail email = pendingEmailService.getPendingEmail(sendId, userEmail)
                    .orElse(null);
            if (email == null) {
                return ResponseEntity.status(404).body(ApiResponse.error("Pending email not found"));
            }

            long now = System.currentTimeMillis();
            long expiresAt = email.getUndoDeadline() != null
                    ? java.time.ZonedDateTime.of(email.getUndoDeadline(), java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    : now;
            int remainingSeconds = Math.max(0, (int) Math.ceil((expiresAt - now) / 1000.0));

            Map<String, Object> data = new HashMap<>();
            data.put("sendId", email.getSendId());
            data.put("status", email.getStatus());
            data.put("undoDeadline", email.getUndoDeadline() != null ? email.getUndoDeadline().toString() : null);
            data.put("from", email.getUserEmail());
            data.put("fromName", email.getFromName());
            data.put("to", email.getToRecipient());
            data.put("cc", email.getCc());
            data.put("bcc", email.getBcc());
            data.put("subject", email.getSubject());
            data.put("body", email.getBody());
            data.put("isHtml", email.getIsHtml());
            data.put("draftUid", email.getDraftUid());
            data.put("draftId", email.getDraftId());
            data.put("attachments", pendingEmailService.parseAttachments(email.getAttachmentsJson()));
            data.put("expiresAt", expiresAt);
            data.put("remainingSeconds", remainingSeconds);
            data.put("isStarred", email.getIsStarred());
            data.put("starred", email.getIsStarred());

            return ResponseEntity.ok(ApiResponse.success(data, "Pending email details"));
        } catch (Exception e) {
            log.warn("Failed to get pending email: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.error("Failed to get pending email: " + e.getMessage()));
        }
    }

    /**
     * Get all active PENDING_UNDO emails for the authenticated user.
     * Enables restoring Undo Send countdown on page refresh or navigation.
     */
    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getActivePendingEmails(Authentication authentication) {
        try {
            if (authentication == null || authentication.getName() == null) {
                return ResponseEntity.ok(ApiResponse.success(new ArrayList<>(), "Active pending emails"));
            }
            String userEmail = authentication.getName();
            List<com.btctech.mailapp.entity.PendingEmail> list = pendingEmailService.getActivePendingEmails(userEmail);
            List<Map<String, Object>> result = new ArrayList<>();
            long now = System.currentTimeMillis();

            if (list != null) {
                for (com.btctech.mailapp.entity.PendingEmail email : list) {
                    if (email == null) continue;
                    long expiresAt = email.getUndoDeadline() != null
                            ? java.time.ZonedDateTime.of(email.getUndoDeadline(), java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                            : now;
                    int remainingSeconds = Math.max(0, (int) Math.ceil((expiresAt - now) / 1000.0));

                    Map<String, Object> data = new HashMap<>();
                    data.put("sendId", email.getSendId());
                    data.put("status", email.getStatus());
                    data.put("undoDeadline", email.getUndoDeadline() != null ? email.getUndoDeadline().toString() : null);
                    data.put("from", email.getUserEmail());
                    data.put("fromName", email.getFromName());
                    data.put("to", email.getToRecipient());
                    data.put("cc", email.getCc());
                    data.put("bcc", email.getBcc());
                    data.put("subject", email.getSubject());
                    data.put("body", email.getBody());
                    data.put("isHtml", email.getIsHtml());
                    data.put("draftUid", email.getDraftUid());
                    data.put("draftId", email.getDraftId());
                    data.put("attachments", pendingEmailService.parseAttachments(email.getAttachmentsJson()));
                    data.put("expiresAt", expiresAt);
                    data.put("remainingSeconds", remainingSeconds);
                    data.put("isStarred", email.getIsStarred());
                    data.put("starred", email.getIsStarred());
                    result.add(data);
                }
            }

            return ResponseEntity.ok(ApiResponse.success(result, "Active pending emails"));
        } catch (Exception e) {
            log.error("Failed to retrieve pending emails: {}", e.getMessage(), e);
            // Return 200 with empty list instead of 400 Bad Request to prevent UI breakage
            return ResponseEntity.ok(ApiResponse.success(new ArrayList<>(), "Active pending emails"));
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