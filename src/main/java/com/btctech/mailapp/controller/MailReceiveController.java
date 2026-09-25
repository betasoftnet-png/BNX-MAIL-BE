package com.btctech.mailapp.controller;

import com.btctech.mailapp.dto.ApiResponse;
import com.btctech.mailapp.dto.EmailDTO;
import com.btctech.mailapp.dto.InboxResponse;
import com.btctech.mailapp.dto.SendMailRequest;
import com.btctech.mailapp.service.MailReceiveService;
import com.btctech.mailapp.service.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/mail")
@RequiredArgsConstructor
public class MailReceiveController {

    private final MailReceiveService mailReceiveService;
    private final SessionService sessionService;

    /**
     * Get inbox emails
     */
    @GetMapping("/inbox")
    public ResponseEntity<ApiResponse<InboxResponse>> getInbox(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String category,
            @RequestHeader("Authorization") String authHeader,
            Authentication authentication) {

        try {
            String email = authentication.getName();
            log.info("Get inbox request from: {}", email);

            // Get password from session
            String token = authHeader.substring(7);
            String password = sessionService.getPasswordFromSession(token);

            if (password == null) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("Session expired. Please login again."));
            }

            // Fetch emails
            com.btctech.mailapp.dto.FolderResult result = mailReceiveService.getInbox(email, password, page, limit);
            List<EmailDTO> emails = result.getEmails() != null ? result.getEmails() : new java.util.ArrayList<>();
            int totalCount = result.getTotalCount();

            // Filter by category if provided
            if (category != null && !category.isEmpty()) {
                emails = emails.stream()
                        .filter(e -> category.equalsIgnoreCase(e.getCategory()))
                        .toList();
                log.info("Filtered inbox to {} emails for category: {}", emails.size(), category);
            }

            // Get unread count
            int unreadCount = mailReceiveService.getUnreadCount(email, password);

            // Build response
            InboxResponse response = InboxResponse.builder()
                    .email(email)
                    .totalCount(totalCount)
                    .unreadCount(unreadCount)
                    .emails(emails)
                    .build();

            log.info("✓ Fetched {} emails for {}", emails.size(), email);

            return ResponseEntity.ok(
                    ApiResponse.success(response, "Inbox fetched successfully"));

        } catch (Throwable e) {
            String userEmail = (authentication != null) ? authentication.getName() : "Unknown User";
            log.error("CRITICAL error fetching inbox for {}: {}", userEmail, e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Failed to fetch inbox: " + e.getMessage()));
        }
    }

    /**
     * Get unread emails
     */
    @GetMapping("/unread")
    public ResponseEntity<ApiResponse<InboxResponse>> getUnread(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader("Authorization") String authHeader,
            Authentication authentication) {

        try {
            String email = authentication.getName();
            log.info("Get unread emails request from: {}", email);

            // Get password from session
            String token = authHeader.substring(7);
            String password = sessionService.getPasswordFromSession(token);

            if (password == null) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("Session expired. Please login again."));
            }

            // Fetch emails
            com.btctech.mailapp.dto.FolderResult result = mailReceiveService.getUnreadEmails(email, password, page, limit);
            List<EmailDTO> emails = result.getEmails() != null ? result.getEmails() : new java.util.ArrayList<>();
            int totalCount = result.getTotalCount();

            // Build response
            InboxResponse response = InboxResponse.builder()
                    .email(email)
                    .totalCount(totalCount)
                    .unreadCount(totalCount)
                    .emails(emails)
                    .build();

            log.info("✓ Fetched {} unread emails for {}", emails.size(), email);

            return ResponseEntity.ok(
                    ApiResponse.success(response, "Unread emails fetched successfully"));

        } catch (Throwable e) {
            String userEmail = (authentication != null) ? authentication.getName() : "Unknown User";
            log.error("CRITICAL error fetching unread emails for {}: {}", userEmail, e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Failed to fetch unread emails: " + e.getMessage()));
        }
    }

    @GetMapping("/analytics")
    public ResponseEntity<ApiResponse<com.btctech.mailapp.dto.AnalyticsDTO>> getAnalytics(
            @RequestParam(required = false) String timezone,
            @RequestHeader("Authorization") String authHeader,
            Authentication authentication) {
        try {
            String email = authentication.getName();
            log.info("Get analytics request from: {} with timezone: {}", email, timezone);

            String token = authHeader.substring(7);
            String password = sessionService.getPasswordFromSession(token);

            if (password == null) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("Session expired. Please login again."));
            }

            com.btctech.mailapp.dto.AnalyticsDTO analytics = mailReceiveService.getAnalytics(email, password, timezone);

            log.info("✓ Fetched analytics for {}", email);
            return ResponseEntity.ok(ApiResponse.success(analytics, "Analytics fetched successfully"));

        } catch (Throwable e) {
            String userEmail = (authentication != null) ? authentication.getName() : "Unknown User";
            log.error("CRITICAL error fetching analytics for {}: {}", userEmail, e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Failed to fetch analytics: " + e.getMessage()));
        }
    }

    /**
     * Get storage quota
     */
    @GetMapping("/storage-quota")
    public ResponseEntity<ApiResponse<com.btctech.mailapp.dto.StorageQuotaDTO>> getStorageQuota(
            @RequestHeader("Authorization") String authHeader,
            Authentication authentication) {
        try {
            String email = authentication.getName();
            log.info("Get storage quota request from: {}", email);

            String token = authHeader.substring(7);
            String password = null;
            try {
                password = sessionService.getPasswordFromSession(token);
            } catch (Exception e) {
                log.info("Could not extract password from session (likely OAuth token). Proceeding with doveadm fallback.");
            }

            com.btctech.mailapp.dto.StorageQuotaDTO quota = mailReceiveService.getStorageQuota(email, password);
            return ResponseEntity.ok(ApiResponse.success(quota, "Storage quota fetched successfully"));

        } catch (Throwable e) {
            log.error("Error fetching storage quota: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Failed to fetch storage quota: " + e.getMessage()));
        }
    }

    /**
     * Get emails by category (Social, Promotions, Updates, etc.)
     */
    @GetMapping("/category/{category}")
    public ResponseEntity<ApiResponse<InboxResponse>> getEmailsByCategory(
            @PathVariable String category,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader("Authorization") String authHeader,
            Authentication authentication) {
        
        try {
            String email = authentication.getName();
            log.info("Fetching emails for category: {} for user: {}", category, email);

            String token = authHeader.substring(7);
            String password = sessionService.getPasswordFromSession(token);

            if (password == null) {
                return ResponseEntity.status(401)
                        .body(ApiResponse.error("Session expired. Please login again."));
            }

            com.btctech.mailapp.dto.FolderResult result = mailReceiveService.getEmailsByCategory(email, password, category, page, limit);
            List<EmailDTO> emails = result.getEmails() != null ? result.getEmails() : new java.util.ArrayList<>();
            int totalCount = result.getTotalCount();

            InboxResponse response = InboxResponse.builder()
                    .email(email)
                    .totalCount(totalCount)
                    .unreadCount(0) // Logic for per-category unread count can be added later
                    .emails(emails)
                    .build();

            return ResponseEntity.ok(
                    ApiResponse.success(response, "Category " + category + " fetched successfully"));

        } catch (Throwable e) {
            log.error("CRITICAL error fetching category {}: {}", category, e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Failed to fetch categorized emails: " + e.getMessage()));
        }
    }

    /**
     * Get sent emails
     */
    @GetMapping("/sent")
    public ResponseEntity<ApiResponse<InboxResponse>> getSent(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader("Authorization") String authHeader,
            Authentication authentication) {

        try {
            String email = authentication.getName();
            log.info("Get sent emails request from: {}", email);

            // Get password from session
            String token = authHeader.substring(7);
            String password = sessionService.getPasswordFromSession(token);

            if (password == null) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("Session expired. Please login again."));
            }

            // Fetch emails
            com.btctech.mailapp.dto.FolderResult result = mailReceiveService.getSent(email, password, page, limit);
            List<EmailDTO> emails = result.getEmails() != null ? result.getEmails() : new java.util.ArrayList<>();
            int totalCount = result.getTotalCount();

            // Build response
            InboxResponse response = InboxResponse.builder()
                    .email(email)
                    .totalCount(totalCount)
                    .unreadCount(0) // Sent items don't really have "unread" count in this context
                    .emails(emails)
                    .build();

            log.info("✓ Fetched {} sent emails for {}", emails.size(), email);

            return ResponseEntity.ok(
                    ApiResponse.success(response, "Sent emails fetched successfully"));

        } catch (Throwable e) {
            String userEmail = (authentication != null) ? authentication.getName() : "Unknown User";
            log.error("CRITICAL error fetching sent emails for {}: {}", userEmail, e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Failed to fetch sent emails: " + e.getMessage()));
        }
    }

    /**
     * Get emails by label
     */
    @GetMapping("/labels/{labelId}")
    public ResponseEntity<ApiResponse<InboxResponse>> getEmailsByLabel(
            @PathVariable Long labelId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader("Authorization") String authHeader,
            Authentication authentication) {

        try {
            String email = authentication.getName();
            log.info("Get emails for label {} request from: {}", labelId, email);

            String token = authHeader.substring(7);
            String password = sessionService.getPasswordFromSession(token);

            if (password == null) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("Session expired. Please login again."));
            }

            com.btctech.mailapp.dto.FolderResult result = mailReceiveService.getEmailsByLabel(email, password, labelId, page, limit);
            List<EmailDTO> emails = result.getEmails() != null ? result.getEmails() : new java.util.ArrayList<>();
            int totalCount = result.getTotalCount();

            InboxResponse response = InboxResponse.builder()
                    .email(email)
                    .totalCount(totalCount)
                    .unreadCount(0)
                    .emails(emails)
                    .build();

            log.info("✓ Fetched {} emails for label {} for {}", emails.size(), labelId, email);

            return ResponseEntity.ok(
                    ApiResponse.success(response, "Emails for label fetched successfully"));

        } catch (Throwable e) {
            String userEmail = (authentication != null) ? authentication.getName() : "Unknown User";
            log.error("CRITICAL error fetching emails for label {} for {}: {}", labelId, userEmail, e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Failed to fetch emails for this label: " + e.getMessage()));
        }
    }

    /**
     * Get starred emails
     */
    @GetMapping("/starred")
    public ResponseEntity<ApiResponse<InboxResponse>> getStarred(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader("Authorization") String authHeader,
            Authentication authentication) {

        try {
            String email = authentication.getName();
            log.info("Get starred emails request from: {}", email);

            // Get password from session
            String token = authHeader.substring(7);
            String password = sessionService.getPasswordFromSession(token);

            if (password == null) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("Session expired. Please login again."));
            }

            // Fetch emails
            com.btctech.mailapp.dto.FolderResult result = mailReceiveService.getStarred(email, password, page, limit);
            List<EmailDTO> emails = result.getEmails() != null ? result.getEmails() : new java.util.ArrayList<>();
            int totalCount = result.getTotalCount();

            // Build response
            InboxResponse response = InboxResponse.builder()
                    .email(email)
                    .totalCount(totalCount)
                    .unreadCount(0)
                    .emails(emails)
                    .build();

            log.info("✓ Fetched {} starred emails for {}", emails.size(), email);

            return ResponseEntity.ok(
                    ApiResponse.success(response, "Starred emails fetched successfully"));

        } catch (Throwable e) {
            String userEmail = (authentication != null) ? authentication.getName() : "Unknown User";
            log.error("CRITICAL error fetching starred emails for {}: {}", userEmail, e.getMessage(), e);
            
            // Temporary verbose error for remote debugging
            String stackTrace = java.util.Arrays.toString(e.getStackTrace());
            String debugMessage = String.format("[%s] %s | Stack: %s", 
                e.getClass().getSimpleName(), 
                e.getMessage(), 
                stackTrace.substring(0, Math.min(stackTrace.length(), 200)));

            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Debug Error: " + debugMessage));
        }
    }

    /**
     * Toggle starred status
     */
    @PostMapping("/star/{uid}")
    public ResponseEntity<ApiResponse<Void>> toggleStar(
            @PathVariable String uid,
            @RequestParam(defaultValue = "INBOX") String folder,
            @RequestHeader("Authorization") String authHeader,
            Authentication authentication) {

        try {
            String email = authentication.getName();
            log.info("Toggle star request for {} in folder {} from {}", uid, folder, email);

            // Get password from session
            String token = authHeader.substring(7);
            String password = sessionService.getPasswordFromSession(token);

            if (password == null) {
                return ResponseEntity.status(401)
                        .body(ApiResponse.error("Session expired. Please login again."));
            }

            mailReceiveService.toggleStar(email, password, folder, uid);

            return ResponseEntity.ok(
                    ApiResponse.success(null, "Star status toggled successfully"));

        } catch (Throwable e) {
            String userEmail = (authentication != null) ? authentication.getName() : "Unknown User";
            log.error("CRITICAL error toggling star status for UID {} in folder {} for {}: {}", uid, folder, userEmail, e.getMessage(), e);
            
            // Temporary verbose error for remote debugging
            String stackTrace = java.util.Arrays.toString(e.getStackTrace());
            String debugMessage = String.format("[%s] %s | Stack: %s", 
                e.getClass().getSimpleName(), 
                e.getMessage(), 
                stackTrace.substring(0, Math.min(stackTrace.length(), 200)));

            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Debug Error: " + debugMessage));
        }
    }

    /**
     * Get trash emails
     */
    @GetMapping("/trash")
    public ResponseEntity<ApiResponse<InboxResponse>> getTrash(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader("Authorization") String authHeader,
            Authentication authentication) {

        try {
            String email = authentication.getName();
            log.info("Get trash emails request from: {}", email);

            String token = authHeader.substring(7);
            String password = sessionService.getPasswordFromSession(token);

            if (password == null) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("Session expired. Please login again."));
            }

            com.btctech.mailapp.dto.FolderResult result = mailReceiveService.getTrash(email, password, page, limit);
            List<EmailDTO> emails = result.getEmails() != null ? result.getEmails() : new java.util.ArrayList<>();
            int totalCount = result.getTotalCount();

            InboxResponse response = InboxResponse.builder()
                    .email(email)
                    .totalCount(totalCount)
                    .unreadCount(0)
                    .emails(emails)
                    .build();

            log.info("✓ Fetched {} trash emails for {}", emails.size(), email);

            return ResponseEntity.ok(
                    ApiResponse.success(response, "Trash emails fetched successfully"));

        } catch (Throwable e) {
            String userEmail = (authentication != null) ? authentication.getName() : "Unknown User";
            log.error("CRITICAL error fetching trash emails for {}: {}", userEmail, e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Failed to fetch trash emails: " + e.getMessage()));
        }
    }

    /**
     * Get spam emails
     */
    @GetMapping("/spam")
    public ResponseEntity<ApiResponse<InboxResponse>> getSpam(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader("Authorization") String authHeader,
            Authentication authentication) {

        try {
            String email = authentication.getName();
            log.info("Get spam emails request from: {}", email);

            String token = authHeader.substring(7);
            String password = sessionService.getPasswordFromSession(token);

            if (password == null) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("Session expired. Please login again."));
            }

            com.btctech.mailapp.dto.FolderResult result = mailReceiveService.getSpam(email, password, page, limit);
            List<EmailDTO> emails = result.getEmails() != null ? result.getEmails() : new java.util.ArrayList<>();
            int totalCount = result.getTotalCount();

            InboxResponse response = InboxResponse.builder()
                    .email(email)
                    .totalCount(totalCount)
                    .unreadCount(0)
                    .emails(emails)
                    .build();

            log.info("✓ Fetched {} spam emails for {}", emails.size(), email);

            return ResponseEntity.ok(
                    ApiResponse.success(response, "Spam emails fetched successfully"));

        } catch (Throwable e) {
            String userEmail = (authentication != null) ? authentication.getName() : "Unknown User";
            log.error("CRITICAL error fetching spam emails for {}: {}", userEmail, e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Failed to fetch spam emails: " + e.getMessage()));
        }
    }

    /**
     * Get snoozed emails
     */
    @GetMapping("/snoozed")
    public ResponseEntity<ApiResponse<InboxResponse>> getSnoozed(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader("Authorization") String authHeader,
            Authentication authentication) {

        try {
            String email = authentication.getName();
            log.info("Get snoozed emails request from: {}", email);

            String token = authHeader.substring(7);
            String password = sessionService.getPasswordFromSession(token);

            if (password == null) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("Session expired. Please login again."));
            }

            com.btctech.mailapp.dto.FolderResult result = mailReceiveService.getSnoozed(email, password, page, limit);
            List<EmailDTO> emails = result.getEmails() != null ? result.getEmails() : new java.util.ArrayList<>();
            int totalCount = result.getTotalCount();

            InboxResponse response = InboxResponse.builder()
                    .email(email)
                    .totalCount(totalCount)
                    .unreadCount(0)
                    .emails(emails)
                    .build();

            log.info("✓ Fetched {} snoozed emails for {}", emails.size(), email);

            return ResponseEntity.ok(
                    ApiResponse.success(response, "Snoozed emails fetched successfully"));

        } catch (Throwable e) {
            String userEmail = (authentication != null) ? authentication.getName() : "Unknown User";
            log.error("CRITICAL error fetching snoozed emails for {}: {}", userEmail, e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Failed to fetch snoozed emails: " + e.getMessage()));
        }
    }

    /**
     * Get archived emails
     */
    @GetMapping("/archive")
    public ResponseEntity<ApiResponse<InboxResponse>> getArchive(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader("Authorization") String authHeader,
            Authentication authentication) {

        try {
            String email = authentication.getName();
            log.info("Get archive emails request from: {}", email);

            String token = authHeader.substring(7);
            String password = sessionService.getPasswordFromSession(token);

            if (password == null) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("Session expired. Please login again."));
            }

            com.btctech.mailapp.dto.FolderResult result = mailReceiveService.getArchive(email, password, page, limit);
            List<EmailDTO> emails = result.getEmails() != null ? result.getEmails() : new java.util.ArrayList<>();
            int totalCount = result.getTotalCount();

            InboxResponse response = InboxResponse.builder()
                    .email(email)
                    .totalCount(totalCount)
                    .unreadCount(0)
                    .emails(emails)
                    .build();

            log.info("✓ Fetched {} archived emails for {}", emails.size(), email);

            return ResponseEntity.ok(
                    ApiResponse.success(response, "Archived emails fetched successfully"));

        } catch (Throwable e) {
            String userEmail = (authentication != null) ? authentication.getName() : "Unknown User";
            log.error("CRITICAL error fetching archived emails for {}: {}", userEmail, e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Failed to fetch archived emails: " + e.getMessage()));
        }
    }

    /**
     * Get drafts from IMAP server
     */
    @GetMapping("/draft")
    public ResponseEntity<ApiResponse<InboxResponse>> getDrafts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader("Authorization") String authHeader,
            Authentication authentication) {

        try {
            String email = authentication.getName();
            log.info("Get drafts request from: {}", email);

            String token = authHeader.substring(7);
            String password = sessionService.getPasswordFromSession(token);

            if (password == null) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("Session expired. Please login again."));
            }

            com.btctech.mailapp.dto.FolderResult result = mailReceiveService.getDrafts(email, password, page, limit);
            List<EmailDTO> emails = result.getEmails() != null ? result.getEmails() : new java.util.ArrayList<>();
            int totalCount = result.getTotalCount();

            InboxResponse response = InboxResponse.builder()
                    .email(email)
                    .totalCount(totalCount)
                    .unreadCount(0)
                    .emails(emails)
                    .build();

            log.info("✓ Fetched {} drafts for {}", emails.size(), email);

            return ResponseEntity.ok(
                    ApiResponse.success(response, "Drafts fetched successfully"));

        } catch (Throwable e) {
            String userEmail = (authentication != null) ? authentication.getName() : "Unknown User";
            log.error("CRITICAL error fetching drafts for {}: {}", userEmail, e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Failed to fetch drafts: " + e.getMessage()));
        }
    }

    /**
     * Save draft to IMAP server
     */
    @PostMapping("/draft")
    public ResponseEntity<ApiResponse<Void>> saveDraft(
            @RequestBody SendMailRequest request,
            @RequestHeader("Authorization") String authHeader,
            Authentication authentication) {

        try {
            String email = authentication.getName();
            log.info("Save draft request from: {}", email);

            String token = authHeader.substring(7);
            String password = sessionService.getPasswordFromSession(token);

            if (password == null) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("Session expired. Please login again."));
            }

            mailReceiveService.saveDraftToIMAP(email, password, request);

            return ResponseEntity.ok(
                    ApiResponse.success(null, "Draft saved successfully"));

        } catch (Throwable e) {
            String userEmail = (authentication != null) ? authentication.getName() : "Unknown User";
            log.error("CRITICAL error saving draft for {}: {}", userEmail, e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Failed to save draft: " + e.getMessage()));
        }
    }




    /**
     * Move to trash
     */
    @PostMapping("/trash/{uid}")
    public ResponseEntity<ApiResponse<Void>> moveToTrash(
            @PathVariable String uid,
            @RequestParam(defaultValue = "INBOX") String folder,
            @RequestHeader("Authorization") String authHeader,
            Authentication authentication) {

        try {
            String email = authentication.getName();
            log.info("Move to trash request for {} in folder {} from {}", uid, folder, email);

            String token = authHeader.substring(7);
            String password = sessionService.getPasswordFromSession(token);

            if (password == null) {
                return ResponseEntity.status(401)
                        .body(ApiResponse.error("Session expired. Please login again."));
            }

            mailReceiveService.moveToTrash(email, password, folder, uid);

            return ResponseEntity.ok(
                    ApiResponse.success(null, "Email moved to trash successfully"));

        } catch (Throwable e) {
            log.error("CRITICAL error moving email UID {} to trash from {}: {}", uid, folder, e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Failed to move email to trash: " + e.getMessage()));
        }
    }

    /**
     * Clear all spam (move all to trash)
     */
    @PostMapping("/spam/clear")
    public ResponseEntity<ApiResponse<Void>> clearSpam(
            @RequestHeader("Authorization") String authHeader,
            Authentication authentication) {

        try {
            String email = authentication.getName();
            log.info("Clear all spam request from {}", email);

            String token = authHeader.substring(7);
            String password = sessionService.getPasswordFromSession(token);

            if (password == null) {
                return ResponseEntity.status(401)
                        .body(ApiResponse.error("Session expired. Please login again."));
            }

            mailReceiveService.clearSpam(email, password);

            return ResponseEntity.ok(
                    ApiResponse.success(null, "All spam moved to trash successfully"));

        } catch (Throwable e) {
            log.error("CRITICAL error clearing spam for {}: {}", authentication.getName(), e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Failed to clear spam: " + e.getMessage()));
        }
    }

    /**
     * Mark as spam
     */
    @PostMapping("/spam/{uid}")
    public ResponseEntity<ApiResponse<Void>> markAsSpam(
            @PathVariable String uid,
            @RequestParam(defaultValue = "INBOX") String folder,
            @RequestHeader("Authorization") String authHeader,
            Authentication authentication) {

        try {
            String email = authentication.getName();
            log.info("Mark as spam request for {} in folder {} from {}", uid, folder, email);

            String token = authHeader.substring(7);
            String password = sessionService.getPasswordFromSession(token);

            if (password == null) {
                return ResponseEntity.status(401)
                        .body(ApiResponse.error("Session expired. Please login again."));
            }

            mailReceiveService.markAsSpam(email, password, folder, uid);

            return ResponseEntity.ok(
                    ApiResponse.success(null, "Email marked as spam successfully"));

        } catch (Throwable e) {
            log.error("CRITICAL error marking email UID {} as spam from {}: {}", uid, folder, e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Failed to mark as spam: " + e.getMessage()));
        }
    }

    /**
     * Snooze email
     */
    @PostMapping("/snooze/{uid}")
    public ResponseEntity<ApiResponse<Void>> snoozeEmail(
            @PathVariable String uid,
            @RequestParam String wakeUpAt,
            @RequestParam(defaultValue = "INBOX") String folder,
            @RequestHeader("Authorization") String authHeader,
            Authentication authentication) {

        try {
            String email = authentication.getName();
            log.info("Snooze request for {} in folder {} until {}", uid, folder, wakeUpAt);

            String token = authHeader.substring(7);
            String password = sessionService.getPasswordFromSession(token);

            if (password == null) {
                return ResponseEntity.status(401)
                        .body(ApiResponse.error("Session expired. Please login again."));
            }

            java.time.LocalDateTime wakeTime;
            try {
                if (wakeUpAt.endsWith("Z")) {
                    wakeTime = java.time.Instant.parse(wakeUpAt).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
                } else if (wakeUpAt.contains("+") || (wakeUpAt.contains("-") && wakeUpAt.lastIndexOf('-') > wakeUpAt.indexOf('T'))) {
                    wakeTime = java.time.ZonedDateTime.parse(wakeUpAt).toLocalDateTime();
                } else {
                    wakeTime = java.time.LocalDateTime.parse(wakeUpAt);
                }
            } catch (Exception parseEx) {
                log.warn("Failed to parse wakeUpAt with standard format, attempting fallback for: {}", wakeUpAt, parseEx);
                String clean = wakeUpAt.replace("Z", "");
                wakeTime = java.time.LocalDateTime.parse(clean);
            }

            mailReceiveService.snoozeEmail(email, password, folder, uid, wakeTime);

            return ResponseEntity.ok(
                    ApiResponse.success(null, "Email snoozed successfully"));

        } catch (Exception e) {
            log.error("CRITICAL error snoozing email UID {}: {}", uid, e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Failed to snooze email: " + e.getMessage()));
        }
    }

    /**
     * Move to archive
     */
    @PostMapping("/archive/{uid}")
    public ResponseEntity<ApiResponse<Void>> archiveEmail(
            @PathVariable String uid,
            @RequestParam(defaultValue = "INBOX") String folder,
            @RequestHeader("Authorization") String authHeader,
            Authentication authentication) {

        try {
            String email = authentication.getName();
            log.info("Archive email request for {} in folder {} from {}", uid, folder, email);

            String token = authHeader.substring(7);
            String password = sessionService.getPasswordFromSession(token);

            if (password == null) {
                return ResponseEntity.status(401)
                        .body(ApiResponse.error("Session expired. Please login again."));
            }

            mailReceiveService.archiveEmail(email, password, folder, uid);

            return ResponseEntity.ok(
                    ApiResponse.success(null, "Email archived successfully"));

        } catch (Throwable e) {
            log.error("CRITICAL error archiving email UID {} from {}: {}", uid, folder, e.getMessage(), e);
            String detail = e.getMessage() + (e.getCause() != null ? " | Cause: " + e.getCause().getMessage() : "");
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Failed to archive email: " + detail));
        }
    }

    /**
     * Restore from archive (Unarchive)
     */
    @PostMapping("/unarchive/{uid}")
    public ResponseEntity<ApiResponse<Void>> unarchiveEmail(
            @PathVariable String uid,
            @RequestHeader("Authorization") String authHeader,
            Authentication authentication) {

        try {
            String email = authentication.getName();
            log.info("Unarchive email request for {} from {}", uid, email);

            String token = authHeader.substring(7);
            String password = sessionService.getPasswordFromSession(token);

            if (password == null) {
                return ResponseEntity.status(401)
                        .body(ApiResponse.error("Session expired. Please login again."));
            }

            mailReceiveService.unarchiveEmail(email, password, uid);

            return ResponseEntity.ok(
                    ApiResponse.success(null, "Email restored to Inbox successfully"));

        } catch (Throwable e) {
            log.error("CRITICAL error unarchiving email UID {}: {}", uid, e.getMessage(), e);
            String detail = e.getMessage() + (e.getCause() != null ? " | Cause: " + e.getCause().getMessage() : "");
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Failed to unarchive email: " + detail));
        }
    }



    /**
     * Restore from trash
     */
    @PostMapping("/restore/{uid}")
    public ResponseEntity<ApiResponse<Void>> restoreFromTrash(
            @PathVariable String uid,
            @RequestHeader("Authorization") String authHeader,
            Authentication authentication) {

        try {
            String email = authentication.getName();
            log.info("Restore from trash request for {} from {}", uid, email);

            String token = authHeader.substring(7);
            String password = sessionService.getPasswordFromSession(token);

            if (password == null) {
                return ResponseEntity.status(401)
                        .body(ApiResponse.error("Session expired. Please login again."));
            }

            mailReceiveService.restoreFromTrash(email, password, uid);

            return ResponseEntity.ok(
                    ApiResponse.success(null, "Email restored successfully"));

        } catch (Throwable e) {
            log.error("CRITICAL error restoring email from trash: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Failed to restore email: " + e.getMessage()));
        }
    }

    /**
     * Restore from spam
     */
    @PostMapping("/restore-spam/{uid}")
    public ResponseEntity<ApiResponse<Void>> restoreFromSpam(
            @PathVariable String uid,
            @RequestHeader("Authorization") String authHeader,
            Authentication authentication) {

        try {
            String email = authentication.getName();
            log.info("Restore from spam request for {} from {}", uid, email);

            String token = authHeader.substring(7);
            String password = sessionService.getPasswordFromSession(token);

            if (password == null) {
                return ResponseEntity.status(401)
                        .body(ApiResponse.error("Session expired. Please login again."));
            }

            mailReceiveService.restoreFromSpam(email, password, uid);

            return ResponseEntity.ok(
                    ApiResponse.success(null, "Email restored from spam successfully"));

        } catch (Throwable e) {
            log.error("CRITICAL error restoring email from spam: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Failed to restore from spam: " + e.getMessage()));
        }
    }

    /**
     * Delete permanently
     */
    @DeleteMapping("/permanent/{uid}")
    public ResponseEntity<ApiResponse<Void>> deletePermanently(
            @PathVariable String uid,
            @RequestHeader("Authorization") String authHeader,
            Authentication authentication) {

        try {
            String email = authentication.getName();
            log.info("Permanent delete request for {} from {}", uid, email);

            String token = authHeader.substring(7);
            String password = sessionService.getPasswordFromSession(token);

            if (password == null) {
                return ResponseEntity.status(401)
                        .body(ApiResponse.error("Session expired. Please login again."));
            }

            mailReceiveService.deletePermanently(email, password, uid);

            return ResponseEntity.ok(
                    ApiResponse.success(null, "Email deleted permanently"));

        } catch (Throwable e) {
            log.error("CRITICAL error deleting email permanently: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Failed to delete email permanently: " + e.getMessage()));
        }
    }

    /**
     * Get single email
     */
    @GetMapping("/email/{uid}")
    public ResponseEntity<ApiResponse<EmailDTO>> getEmail(
            @PathVariable String uid,
            @RequestHeader("Authorization") String authHeader,
            Authentication authentication) {

        try {
            String email = authentication.getName();
            log.info("Get email {} request from: {}", uid, email);

            // Get password from session
            String token = authHeader.substring(7);
            String password = sessionService.getPasswordFromSession(token);

            if (password == null) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("Session expired. Please login again."));
            }

            // Fetch email
            EmailDTO emailDTO = mailReceiveService.getEmail(email, password, uid);

            return ResponseEntity.ok(
                    ApiResponse.success(emailDTO, "Email fetched successfully"));

        } catch (Throwable e) {
            String userEmail = (authentication != null) ? authentication.getName() : "Unknown User";
            log.error("CRITICAL error fetching email {} for {}: {}", uid, userEmail, e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Failed to fetch email: " + e.getMessage()));
        }
    }

    /**
     * Mark email as read
     */
    @PostMapping("/read/{uid}")
    public ResponseEntity<ApiResponse<Void>> markAsRead(
            @PathVariable String uid,
            @RequestHeader("Authorization") String authHeader,
            Authentication authentication) {

        try {
            String email = authentication.getName();
            log.info("Mark as read request for {} from {}", uid, email);

            // Get password from session
            String token = authHeader.substring(7);
            String password = sessionService.getPasswordFromSession(token);

            if (password == null) {
                return ResponseEntity.status(401)
                        .body(ApiResponse.error("Session expired. Please login again."));
            }

            mailReceiveService.markAsRead(email, password, uid);

            return ResponseEntity.ok(
                    ApiResponse.success(null, "Email marked as read"));

        } catch (Throwable e) {
            log.error("CRITICAL error marking email as read: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Failed to mark email as read: " + e.getMessage()));
        }
    }

    /**
     * Mark email as unread
     */
    @PostMapping("/unread/{uid}")
    public ResponseEntity<ApiResponse<Void>> markAsUnread(
            @PathVariable String uid,
            @RequestHeader("Authorization") String authHeader,
            Authentication authentication) {

        try {
            String email = authentication.getName();
            log.info("Mark as unread request for {} from {}", uid, email);

            String token = authHeader.substring(7);
            String password = sessionService.getPasswordFromSession(token);

            if (password == null) {
                return ResponseEntity.status(401)
                        .body(ApiResponse.error("Session expired. Please login again."));
            }

            mailReceiveService.markAsUnread(email, password, uid);

            return ResponseEntity.ok(
                    ApiResponse.success(null, "Email marked as unread"));

        } catch (Throwable e) {
            log.error("CRITICAL error marking email as unread: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Failed to mark email as unread: " + e.getMessage()));
        }
    }

    /**
     * Download attachment from a received email
     */
    @GetMapping("/{uid}/attachments/{fileName}")
    public void downloadAttachment(
            @PathVariable String uid,
            @PathVariable String fileName,
            @RequestParam(defaultValue = "INBOX") String folder,
            @RequestHeader("Authorization") String authHeader,
            Authentication authentication,
            jakarta.servlet.http.HttpServletResponse response) {
        
        String email = authentication.getName();
        String token = authHeader.substring(7);
        String password = sessionService.getPasswordFromSession(token);
        
        log.info("Download attachment request: {} from email: {} (folder: {}) for user: {}", fileName, uid, folder, email);

        if (password == null) {
            log.warn("Unauthorized download attempt (expired session) by {}", email);
            response.setStatus(401);
            return;
        }

        try {
            // Set response headers
            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
            
            // Stream the attachment
            mailReceiveService.downloadAttachment(
                email, 
                password, 
                folder,
                uid, 
                fileName, 
                response.getOutputStream()
            );
            
            response.flushBuffer();
        } catch (Exception e) {
            log.error("Failed to stream attachment: {}", e.getMessage());
            if (!response.isCommitted()) {
                response.setStatus(500);
            }
        }
    }

    @PostMapping("/unsubscribe")
    public ResponseEntity<ApiResponse<Void>> unsubscribe(
            @RequestParam String senderEmail,
            Authentication authentication) {
        try {
            String userEmail = authentication.getName();
            log.info("Unsubscribe request from: {} for sender: {}", userEmail, senderEmail);
            mailReceiveService.unsubscribeSender(userEmail, senderEmail);
            return ResponseEntity.ok(ApiResponse.success(null, "Successfully unsubscribed from " + senderEmail));
        } catch (Exception e) {
            log.error("Error unsubscribing sender: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(ApiResponse.error("Failed to unsubscribe: " + e.getMessage()));
        }
    }

    @PostMapping("/subscribe")
    public ResponseEntity<ApiResponse<Void>> subscribe(
            @RequestParam String senderEmail,
            Authentication authentication) {
        try {
            String userEmail = authentication.getName();
            log.info("Subscribe request from: {} for sender: {}", userEmail, senderEmail);
            mailReceiveService.subscribeSender(userEmail, senderEmail);
            return ResponseEntity.ok(ApiResponse.success(null, "Successfully subscribed to " + senderEmail));
        } catch (Exception e) {
            log.error("Error subscribing sender: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(ApiResponse.error("Failed to subscribe: " + e.getMessage()));
        }
    }

    @GetMapping("/subscriptions")
    public ResponseEntity<ApiResponse<List<String>>> getSubscriptions(
            Authentication authentication) {
        try {
            String userEmail = authentication.getName();
            log.info("Get subscriptions request from: {}", userEmail);
            List<String> blockedSenders = mailReceiveService.getBlockedSenders(userEmail);
            return ResponseEntity.ok(ApiResponse.success(blockedSenders, "Subscriptions fetched successfully"));
        } catch (Exception e) {
            log.error("Error fetching subscriptions: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(ApiResponse.error("Failed to fetch subscriptions: " + e.getMessage()));
        }
    }
}