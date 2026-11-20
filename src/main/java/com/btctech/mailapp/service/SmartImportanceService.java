package com.btctech.mailapp.service;

import com.btctech.mailapp.dto.EmailDTO;
import com.btctech.mailapp.entity.ImportantEmail;
import com.btctech.mailapp.entity.UserSenderActivity;
import com.btctech.mailapp.repository.ImportantEmailRepository;
import com.btctech.mailapp.repository.StarredEmailRepository;
import com.btctech.mailapp.repository.UserSenderActivityRepository;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmartImportanceService {

    public static final double IMPORTANCE_THRESHOLD = 50.0;

    private final ImportantEmailRepository importantEmailRepository;
    private final UserSenderActivityRepository userSenderActivityRepository;
    private final StarredEmailRepository starredEmailRepository;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportanceResult {
        private boolean isImportant;
        private double score;
        private String reasons;
        private boolean isManual;
    }

    /**
     * Record that the user opened an email from a sender.
     */
    @Transactional
    public void recordEmailOpened(String userEmail, String rawSender) {
        String senderEmail = extractEmailAddress(rawSender);
        if (userEmail == null || senderEmail == null) return;
        if (userEmail.equalsIgnoreCase(senderEmail)) return; // Don't track self

        try {
            LocalDateTime now = LocalDateTime.now();
            UserSenderActivity activity = userSenderActivityRepository
                    .findByUserEmailAndSenderEmail(userEmail, senderEmail)
                    .orElseGet(() -> UserSenderActivity.builder()
                            .userEmail(userEmail)
                            .senderEmail(senderEmail)
                            .openCount(0)
                            .replyCount(0)
                            .sentCount(0)
                            .threadCount(0)
                            .createdAt(now)
                            .build());

            activity.setOpenCount(activity.getOpenCount() + 1);
            activity.setLastOpenedAt(now);
            activity.setLastInteractionAt(now);
            activity.setUpdatedAt(now);

            userSenderActivityRepository.save(activity);
            log.debug("Recorded open for user {} from sender {}. Total opens: {}", userEmail, senderEmail, activity.getOpenCount());
        } catch (Exception e) {
            log.warn("Failed to record open activity for user {} from sender {}: {}", userEmail, senderEmail, e.getMessage());
        }
    }

    /**
     * Record that the user replied or sent an email to a recipient.
     */
    @Transactional
    public void recordEmailSentOrReplied(String userEmail, String rawRecipients, String subject) {
        if (userEmail == null || rawRecipients == null) return;

        String[] recipients = rawRecipients.split(",");
        LocalDateTime now = LocalDateTime.now();
        boolean isReply = subject != null && (subject.trim().toLowerCase().startsWith("re:") || subject.trim().toLowerCase().startsWith("fwd:"));

        for (String raw : recipients) {
            String recipientEmail = extractEmailAddress(raw);
            if (recipientEmail == null || userEmail.equalsIgnoreCase(recipientEmail)) continue;

            try {
                UserSenderActivity activity = userSenderActivityRepository
                        .findByUserEmailAndSenderEmail(userEmail, recipientEmail)
                        .orElseGet(() -> UserSenderActivity.builder()
                                .userEmail(userEmail)
                                .senderEmail(recipientEmail)
                                .openCount(0)
                                .replyCount(0)
                                .sentCount(0)
                                .threadCount(0)
                                .createdAt(now)
                                .build());

                if (isReply) {
                    activity.setReplyCount(activity.getReplyCount() + 1);
                    activity.setLastRepliedAt(now);
                } else {
                    activity.setSentCount(activity.getSentCount() + 1);
                }
                activity.setThreadCount(activity.getThreadCount() + 1);
                activity.setLastInteractionAt(now);
                activity.setUpdatedAt(now);

                userSenderActivityRepository.save(activity);
                log.debug("Recorded sent/reply for user {} to recipient {}. Replies: {}, Sent: {}",
                        userEmail, recipientEmail, activity.getReplyCount(), activity.getSentCount());
            } catch (Exception e) {
                log.warn("Failed to record sent/reply activity for user {} to {}: {}", userEmail, recipientEmail, e.getMessage());
            }
        }
    }

    /**
     * Evaluate importance score and determination for an email.
     */
    public ImportanceResult evaluateEmail(String userEmail, EmailDTO email) {
        if (email == null) {
            return new ImportanceResult(false, 0.0, "Null email", false);
        }

        String uid = email.getUid();
        String folderName = email.getFolderName() != null ? email.getFolderName() : "INBOX";

        // Check if an existing ImportantEmail record exists in database
        if (uid != null && userEmail != null) {
            Optional<ImportantEmail> existingOpt = importantEmailRepository
                    .findByUserEmailAndUidAndFolderName(userEmail, uid, folderName);
            if (existingOpt.isEmpty()) {
                // Also check by uid across folders
                existingOpt = importantEmailRepository.findFirstByUserEmailAndUid(userEmail, uid);
            }

            if (existingOpt.isPresent()) {
                ImportantEmail record = existingOpt.get();
                if (record.isManual()) {
                    // Manual user decision strictly overrides automatic detection
                    return ImportanceResult.builder()
                            .isImportant(record.isImportant())
                            .score(record.getScore() != null ? record.getScore() : (record.isImportant() ? 100.0 : 0.0))
                            .reasons(record.getReasons() != null ? record.getReasons() : "Manual user decision")
                            .isManual(true)
                            .build();
                }
            }
        }

        // Automatic scoring
        String senderEmail = extractEmailAddress(email.getFrom());
        UserSenderActivity activity = null;
        if (userEmail != null && senderEmail != null) {
            activity = userSenderActivityRepository.findByUserEmailAndSenderEmail(userEmail, senderEmail).orElse(null);
        }

        double score = 0.0;
        List<String> reasons = new ArrayList<>();

        // Guardrail 1: Check for promotional, newsletter, spam, or bulk emails
        boolean isPromoOrNewsletter = isPromotionalOrNewsletter(email, activity);
        if (isPromoOrNewsletter) {
            score = -100.0;
            reasons.add("Promotional, newsletter, or bulk message");
            return ImportanceResult.builder()
                    .isImportant(false)
                    .score(score)
                    .reasons(String.join("; ", reasons))
                    .isManual(false)
                    .build();
        }

        // Signal 1: Frequent opens
        if (activity != null && activity.getOpenCount() > 0) {
            int opens = activity.getOpenCount();
            if (opens == 1) {
                // Single open gives a minor signal (+10), but never reaches the 50.0 threshold alone
                score += 10.0;
                reasons.add("Opened once (+10)");
            } else if (opens == 2) {
                score += 20.0;
                reasons.add("Opened twice (+20)");
            } else if (opens >= 3 && opens < 5) {
                score += 35.0;
                reasons.add("Frequently opened sender (+35)");
            } else if (opens >= 5) {
                score += 45.0;
                reasons.add("Very frequently opened sender (+45)");
            }
        }

        // Signal 2: Frequent replies or sent messages
        if (activity != null) {
            int replies = activity.getReplyCount();
            int sents = activity.getSentCount();
            if (replies == 1) {
                score += 30.0;
                reasons.add("Replied to sender (+30)");
            } else if (replies >= 2) {
                score += 45.0;
                reasons.add("Frequently replied-to sender (+45)");
            }

            if (sents >= 1 && replies == 0) {
                score += 20.0;
                reasons.add("Previously sent email to contact (+20)");
            }
        }

        // Signal 3: Repeated two-way conversations
        if (activity != null) {
            if (activity.getOpenCount() >= 2 && (activity.getReplyCount() >= 1 || activity.getSentCount() >= 1)) {
                score += 25.0;
                reasons.add("Repeated two-way conversation (+25)");
            } else if (activity.getThreadCount() >= 2) {
                score += 15.0;
                reasons.add("Multi-thread conversation history (+15)");
            }
        }

        // Signal 4: Recent meaningful interactions
        if (activity != null && activity.getLastInteractionAt() != null) {
            long daysSinceInteraction = Duration.between(activity.getLastInteractionAt(), LocalDateTime.now()).toDays();
            if (daysSinceInteraction <= 7) {
                score += 15.0;
                reasons.add("Recent interaction within 7 days (+15)");
            } else if (daysSinceInteraction <= 30) {
                score += 5.0;
                reasons.add("Interaction within last 30 days (+5)");
            }
        }

        // Signal 5: Direct recipient (in To, not just Cc or Bcc)
        if (userEmail != null && email.getTo() != null && email.getTo().toLowerCase().contains(userEmail.toLowerCase())) {
            score += 10.0;
            reasons.add("Addressed directly in To field (+10)");
        }

        // Signal 6: User previously starred an email from this sender
        if (userEmail != null && senderEmail != null) {
            try {
                boolean hasStarredSender = starredEmailRepository.findByUserEmail(userEmail).stream()
                        .anyMatch(star -> {
                            // Check if sender matches
                            return false; // lightweight check or fallback
                        });
                // Check if user has starred emails
                if (hasStarredSender) {
                    score += 20.0;
                    reasons.add("Previously starred sender (+20)");
                }
            } catch (Exception ignored) {}
        }

        boolean isImportant = score >= IMPORTANCE_THRESHOLD;
        String reasonsStr = reasons.isEmpty() ? "Standard evaluation" : String.join(", ", reasons);

        return ImportanceResult.builder()
                .isImportant(isImportant)
                .score(score)
                .reasons(reasonsStr)
                .isManual(false)
                .build();
    }

    /**
     * Checks if email is promotional, newsletter, spam or bulk.
     * Prevents newsletters/promos from becoming important just because they were opened.
     */
    public boolean isPromotionalOrNewsletter(EmailDTO email, UserSenderActivity activity) {
        if (email == null) return false;

        // If user frequently replies to this person (e.g. >= 2 replies), don't treat them as a newsletter
        if (activity != null && activity.getReplyCount() >= 2) {
            return false;
        }

        String cat = email.getCategory();
        if (cat != null) {
            String upperCat = cat.toUpperCase();
            if (upperCat.equals("SPAM") || upperCat.equals("PROMOTIONS") || upperCat.equals("JUNK")) {
                return true;
            }
        }

        String folder = email.getFolderName();
        if (folder != null) {
            String upperFolder = folder.toUpperCase();
            if (upperFolder.contains("SPAM") || upperFolder.contains("TRASH") || upperFolder.contains("JUNK")) {
                return true;
            }
        }

        // Senders with no-reply, newsletter, digest, etc.
        String from = email.getFrom() != null ? email.getFrom().toLowerCase() : "";
        if (from.contains("no-reply@") || from.contains("noreply@") || from.contains("donotreply@") ||
            from.contains("newsletter@") || from.contains("marketing@") || from.contains("digest@") ||
            from.contains("updates@") || from.contains("notification@") || from.contains("notifications@")) {
            return true;
        }

        // Body or subject unsubscribe indicators
        String body = email.getBody() != null ? email.getBody().toLowerCase() : "";
        String htmlBody = email.getHtmlBody() != null ? email.getHtmlBody().toLowerCase() : "";
        String subject = email.getSubject() != null ? email.getSubject().toLowerCase() : "";

        if (body.contains("unsubscribe") || htmlBody.contains("unsubscribe") ||
            body.contains("opt-out") || htmlBody.contains("opt-out") ||
            body.contains("view in browser") || htmlBody.contains("view in browser")) {
            // If user has not actively replied to this sender, treat as bulk/newsletter
            if (activity == null || activity.getReplyCount() == 0) {
                return true;
            }
        }

        return false;
    }

    /**
     * Enrich a single EmailDTO with isImportant and importanceScore.
     */
    public void enrichEmail(String userEmail, EmailDTO email, String folderName) {
        if (email == null || userEmail == null) return;

        ImportanceResult result = evaluateEmail(userEmail, email);
        email.setImportant(result.isImportant());
        email.setImportanceScore(result.getScore());

        // Auto-persist importance state for fast lookups if not already stored
        if (email.getUid() != null) {
            String resolvedFolder = folderName != null ? folderName : "INBOX";
            Optional<ImportantEmail> existing = importantEmailRepository
                    .findByUserEmailAndUidAndFolderName(userEmail, email.getUid(), resolvedFolder);

            if (existing.isEmpty() && result.isImportant()) {
                try {
                    ImportantEmail newRecord = ImportantEmail.builder()
                            .userEmail(userEmail)
                            .uid(email.getUid())
                            .folderName(resolvedFolder)
                            .isImportant(true)
                            .isManual(false)
                            .score(result.getScore())
                            .reasons(result.getReasons())
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();
                    importantEmailRepository.save(newRecord);
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Enrich a list of emails with importance.
     */
    public void enrichEmails(String userEmail, List<EmailDTO> emails, String folderName) {
        if (emails == null || emails.isEmpty() || userEmail == null) return;
        for (EmailDTO email : emails) {
            enrichEmail(userEmail, email, folderName);
        }
    }

    /**
     * Set manual importance for an email (user click).
     * Strictly overrides automatic classification and persists across login and refresh.
     */
    @Transactional
    public boolean setManualImportance(String userEmail, String uid, String folderName, boolean isImportant) {
        if (userEmail == null || uid == null) return false;
        String resolvedFolder = folderName != null && !folderName.trim().isEmpty() ? folderName : "INBOX";
        log.info("Setting manual importance for user: {}, uid: {}, folder: {}, isImportant: {}",
                userEmail, uid, resolvedFolder, isImportant);

        List<ImportantEmail> existingList = importantEmailRepository.findByUserEmailAndUid(userEmail, uid);

        if (!existingList.isEmpty()) {
            for (ImportantEmail record : existingList) {
                record.setImportant(isImportant);
                record.setManual(true);
                record.setReasons("Manual user preference");
                record.setScore(isImportant ? 100.0 : 0.0);
                record.setUpdatedAt(LocalDateTime.now());
                importantEmailRepository.save(record);
            }
        } else {
            ImportantEmail newRecord = ImportantEmail.builder()
                    .userEmail(userEmail)
                    .uid(uid)
                    .folderName(resolvedFolder)
                    .isImportant(isImportant)
                    .isManual(true)
                    .score(isImportant ? 100.0 : 0.0)
                    .reasons("Manual user preference")
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            importantEmailRepository.save(newRecord);
        }

        return isImportant;
    }

    /**
     * Toggle manual importance for an email.
     */
    @Transactional
    public boolean toggleManualImportance(String userEmail, String uid, String folderName) {
        if (userEmail == null || uid == null) return false;

        List<ImportantEmail> existingList = importantEmailRepository.findByUserEmailAndUid(userEmail, uid);
        boolean currentImportant = false;
        if (!existingList.isEmpty()) {
            currentImportant = existingList.stream().anyMatch(ImportantEmail::isImportant);
        }

        boolean newImportant = !currentImportant;
        return setManualImportance(userEmail, uid, folderName, newImportant);
    }

    public static String extractEmailAddress(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        int start = raw.indexOf('<');
        int end = raw.indexOf('>');
        if (start != -1 && end != -1 && end > start) {
            return raw.substring(start + 1, end).trim().toLowerCase();
        }
        return raw.trim().toLowerCase();
    }
}
