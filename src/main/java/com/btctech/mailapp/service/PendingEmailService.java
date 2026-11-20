package com.btctech.mailapp.service;

import com.btctech.mailapp.dto.AttachmentInfo;
import com.btctech.mailapp.dto.SendMailRequest;
import com.btctech.mailapp.entity.MailAccount;
import com.btctech.mailapp.entity.PendingEmail;
import com.btctech.mailapp.exception.MailException;
import com.btctech.mailapp.repository.PendingEmailRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PendingEmailService {

    private final PendingEmailRepository pendingEmailRepository;
    private final MailSendService mailSendService;
    private final SessionService sessionService;
    private final MailboxService mailboxService;
    private final ObjectMapper objectMapper;

    private final ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(4);
    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> sendLocks = new ConcurrentHashMap<>();

    private Object getLock(String sendId) {
        return sendLocks.computeIfAbsent(sendId, k -> new Object());
    }

    /**
     * Enqueue an email with 5-second undo send delay.
     * Guaranteed atomic idempotency by sendId.
     */
    @Transactional
    public PendingEmail queuePendingEmail(
            String fromEmail,
            Long userId,
            Long mailAccountId,
            String password,
            SendMailRequest request,
            int delaySeconds) {

        String sendId = request.getSendId();
        if (sendId == null || sendId.trim().isEmpty()) {
            sendId = UUID.randomUUID().toString();
            request.setSendId(sendId);
        }

        // Idempotency check: if request with this sendId is already recorded, return it
        Optional<PendingEmail> existingOpt = pendingEmailRepository.findBySendId(sendId);
        if (existingOpt.isPresent()) {
            log.info("Duplicate send request detected for sendId: {}. Returning existing record.", sendId);
            return existingOpt.get();
        }

        String encryptedPassword = null;
        if (password != null && !password.isEmpty()) {
            try {
                encryptedPassword = sessionService.encrypt(password);
            } catch (Exception e) {
                log.warn("Could not encrypt password for pending email (will fallback during send): {}", e.getMessage());
            }
        }

        String attachmentsJson = null;
        if (request.getAttachments() != null && !request.getAttachments().isEmpty()) {
            try {
                attachmentsJson = objectMapper.writeValueAsString(request.getAttachments());
            } catch (Exception e) {
                log.warn("Failed to serialize attachments for pending email: {}", e.getMessage());
            }
        }

        String draftUid = (request.getDraftUid() != null && !request.getDraftUid().isEmpty())
                ? request.getDraftUid()
                : (request.getUid() != null && !request.getUid().isEmpty() ? request.getUid() : null);

        boolean isStarred = Boolean.TRUE.equals(request.getIsStarred()) || Boolean.TRUE.equals(request.getStarred());

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime undoDeadline = now.plusSeconds(Math.max(1, delaySeconds));

        PendingEmail pendingEmail = PendingEmail.builder()
                .sendId(sendId)
                .userEmail(fromEmail)
                .userId(userId)
                .mailAccountId(mailAccountId)
                .encryptedPassword(encryptedPassword)
                .toRecipient(request.getTo())
                .cc(request.getCc())
                .bcc(request.getBcc())
                .subject(request.getSubject())
                .body(request.getBody())
                .fromName(request.getFromName())
                .isHtml(request.getIsHtml() != null ? request.getIsHtml() : true)
                .attachmentsJson(attachmentsJson)
                .draftUid(draftUid)
                .draftId(request.getDraftId() != null ? request.getDraftId() : request.getId())
                .isStarred(isStarred)
                .requestReadReceipt(Boolean.TRUE.equals(request.getRequestReadReceipt()))
                .readReceiptId(request.getReadReceiptId())
                .status("PENDING_UNDO")
                .undoDeadline(undoDeadline)
                .createdAt(now)
                .updatedAt(now)
                .build();

        pendingEmail = pendingEmailRepository.save(pendingEmail);
        log.info("Queued pending email {} from {} to {} with {}s undo window (deadline at {})",
                sendId, fromEmail, request.getTo(), delaySeconds, undoDeadline);

        // Schedule exact in-memory trigger
        final String finalSendId = sendId;
        long delayMillis = delaySeconds * 1000L;
        ScheduledFuture<?> future = scheduledExecutor.schedule(() -> {
            try {
                deliverPendingEmail(finalSendId);
            } catch (Exception e) {
                log.error("Scheduled delivery failed for sendId {}: {}", finalSendId, e.getMessage(), e);
            } finally {
                scheduledTasks.remove(finalSendId);
            }
        }, delayMillis, TimeUnit.MILLISECONDS);

        scheduledTasks.put(finalSendId, future);

        return pendingEmail;
    }

    /**
     * Cancel a pending email delivery within the 5-second window.
     * Undo succeeds ONLY when the email is still PENDING_UNDO and current server time is strictly before undoDeadline.
     * Uses atomic state transition PENDING_UNDO -> CANCELLED.
     */
    @Transactional
    public PendingEmail cancelPendingEmail(String sendId, String userEmail) {
        log.info("Cancellation requested for sendId: {} by user: {}", sendId, userEmail);

        synchronized (getLock(sendId)) {
            // Cancel in-memory scheduled future if still waiting
            ScheduledFuture<?> future = scheduledTasks.remove(sendId);
            if (future != null) {
                future.cancel(false);
            }

            LocalDateTime now = LocalDateTime.now();

            // Atomic DB update: only succeeds if status is PENDING_UNDO AND undo_deadline > now
            int updated = pendingEmailRepository.cancelIfPendingAndBeforeDeadline(sendId, now);

            if (updated == 1) {
                log.info("✓ Successfully cancelled pending email {}", sendId);
                sendLocks.remove(sendId);
                return pendingEmailRepository.findBySendIdAndUserEmail(sendId, userEmail)
                        .orElseThrow(() -> new MailException("Pending email not found"));
            }

            // Could not update - inspect why to return clear, accurate error response
            PendingEmail pendingEmail = pendingEmailRepository.findBySendIdAndUserEmail(sendId, userEmail)
                    .orElseThrow(() -> new MailException("Pending email not found"));

            if ("CANCELLED".equalsIgnoreCase(pendingEmail.getStatus())) {
                log.info("Email {} was already cancelled", sendId);
                return pendingEmail;
            }

            if ("SENDING".equalsIgnoreCase(pendingEmail.getStatus()) || "SENT".equalsIgnoreCase(pendingEmail.getStatus())) {
                log.warn("Cannot cancel sendId {}: Email is already in state {}", sendId, pendingEmail.getStatus());
                throw new MailException("Message is already being sent or has already been delivered. Undo is no longer available.");
            }

            if ("PENDING_UNDO".equalsIgnoreCase(pendingEmail.getStatus()) && !now.isBefore(pendingEmail.getUndoDeadline())) {
                log.warn("Cannot cancel sendId {}: Deadline {} has expired (server time: {})", sendId, pendingEmail.getUndoDeadline(), now);
                throw new MailException("The 5-second cancellation window has expired. The message is now being sent.");
            }

            throw new MailException("Cannot undo email with status: " + pendingEmail.getStatus());
        }
    }

    /**
     * Deliver the email to recipient.
     * Guarantees email is not delivered before undoDeadline.
     * Atomically transitions PENDING_UNDO -> SENDING before initiating SMTP/IMAP send.
     */
    public void deliverPendingEmail(String sendId) {
        synchronized (getLock(sendId)) {
            LocalDateTime now = LocalDateTime.now();

            // Atomic transition: only claims if PENDING_UNDO AND undo_deadline <= now
            int updated = pendingEmailRepository.claimForDeliveryIfDue(sendId, now);
            if (updated == 0) {
                log.debug("Skip delivery for sendId {}: not due or not in PENDING_UNDO state (already cancelled or sending)", sendId);
                return;
            }

            log.info("Initiating actual email delivery for sendId {}", sendId);
            PendingEmail pendingEmail = pendingEmailRepository.findBySendId(sendId).orElse(null);
            if (pendingEmail == null) {
                log.error("Pending email not found during delivery: {}", sendId);
                sendLocks.remove(sendId);
                return;
            }

            try {
                // Retrieve password
                String password = null;
                if (pendingEmail.getEncryptedPassword() != null && !pendingEmail.getEncryptedPassword().isEmpty()) {
                    try {
                        password = sessionService.decrypt(pendingEmail.getEncryptedPassword());
                    } catch (Exception e) {
                        log.warn("Failed to decrypt stored password for sendId {}: {}", sendId, e.getMessage());
                    }
                }

                if (password == null && pendingEmail.getUserId() != null) {
                    password = sessionService.getPasswordByUserId(pendingEmail.getUserId());
                }

                if (password == null) {
                    MailAccount mailAccount = mailboxService.getMailAccountByEmail(pendingEmail.getUserEmail());
                    if (mailAccount != null && mailAccount.getEncryptedPassword() != null) {
                        try {
                            password = sessionService.decrypt(mailAccount.getEncryptedPassword());
                        } catch (Exception e) {
                            log.warn("Failed to decrypt mail account password: {}", e.getMessage());
                        }
                    }
                }

                // Reconstruct SendMailRequest
                SendMailRequest request = new SendMailRequest();
                request.setTo(pendingEmail.getToRecipient());
                request.setCc(pendingEmail.getCc());
                request.setBcc(pendingEmail.getBcc());
                request.setSubject(pendingEmail.getSubject());
                request.setBody(pendingEmail.getBody());
                request.setFromName(pendingEmail.getFromName());
                request.setIsHtml(pendingEmail.getIsHtml());
                request.setDraftUid(pendingEmail.getDraftUid());
                request.setDraftId(pendingEmail.getDraftId());
                request.setIsStarred(pendingEmail.getIsStarred());
                request.setStarred(pendingEmail.getIsStarred());
                request.setRequestReadReceipt(pendingEmail.getRequestReadReceipt());
                request.setReadReceiptId(pendingEmail.getReadReceiptId());

                if (pendingEmail.getAttachmentsJson() != null && !pendingEmail.getAttachmentsJson().trim().isEmpty()) {
                    try {
                        List<AttachmentInfo> attachments = objectMapper.readValue(
                                pendingEmail.getAttachmentsJson(),
                                new TypeReference<List<AttachmentInfo>>() {}
                        );
                        request.setAttachments(attachments);
                    } catch (Exception e) {
                        log.error("Failed to parse attachments JSON for sendId {}: {}", sendId, e.getMessage());
                    }
                }

                // Perform actual email delivery (SMTP send + IMAP Sent archival)
                String sentUid = mailSendService.sendMail(pendingEmail.getUserEmail(), password, request);

                // Mark as SENT
                pendingEmailRepository.markSent(sendId, "SENT", sentUid, LocalDateTime.now());
                log.info("✓ Delivered pending email {} successfully (sentUid={})", sendId, sentUid);

            } catch (Exception e) {
                log.error("Failed to deliver pending email {}: {}", sendId, e.getMessage(), e);
                pendingEmailRepository.markFailed(sendId, "FAILED", e.getMessage(), LocalDateTime.now());
            } finally {
                sendLocks.remove(sendId);
            }
        }
    }

    /**
     * Periodic background recovery poller.
     * Ensures pending messages are delivered reliably even across application restarts.
     */
    @Scheduled(fixedDelay = 2000)
    public void pollPendingDeliveries() {
        LocalDateTime now = LocalDateTime.now();
        List<PendingEmail> dueEmails = pendingEmailRepository.findByStatusAndUndoDeadlineBefore("PENDING_UNDO", now);
        if (dueEmails.isEmpty()) {
            return;
        }

        log.debug("Found {} pending emails due for delivery", dueEmails.size());
        for (PendingEmail email : dueEmails) {
            try {
                deliverPendingEmail(email.getSendId());
            } catch (Exception e) {
                log.error("Recovery delivery failed for sendId {}: {}", email.getSendId(), e.getMessage());
            }
        }
    }

    /**
     * Safety recovery on application startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("Checking for any pending emails after startup...");
        pollPendingDeliveries();
    }

    /**
     * Get pending email by sendId and user email.
     */
    public Optional<PendingEmail> getPendingEmail(String sendId, String userEmail) {
        return pendingEmailRepository.findBySendIdAndUserEmail(sendId, userEmail);
    }

    /**
     * Get all active PENDING_UNDO emails for the given user.
     */
    public List<PendingEmail> getActivePendingEmails(String userEmail) {
        return pendingEmailRepository.findByUserEmailAndStatus(userEmail, "PENDING_UNDO");
    }

    /**
     * Parse attachments safely from json.
     */
    public List<AttachmentInfo> parseAttachments(String attachmentsJson) {
        if (attachmentsJson == null || attachmentsJson.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(attachmentsJson, new TypeReference<List<AttachmentInfo>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse attachments: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduledExecutor.shutdown();
        try {
            if (!scheduledExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduledExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduledExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
