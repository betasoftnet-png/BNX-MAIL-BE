package com.btctech.mailapp.service;

import com.btctech.mailapp.dto.SpamClassificationResult;
import com.btctech.mailapp.dto.SpamClassificationResult.Verdict;
import com.btctech.mailapp.entity.BlockedContact;
import com.btctech.mailapp.entity.MailAccount;
import com.btctech.mailapp.entity.Report;
import com.btctech.mailapp.entity.SenderReputation;
import com.btctech.mailapp.repository.*;
import jakarta.mail.*;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class SpamClassifierService {

    private final BlockedContactRepository blockedContactRepository;
    private final SenderReputationRepository senderReputationRepository;
    private final ReportRepository reportRepository;
    private final MailAccountRepository mailAccountRepository;
    private final ConnectionRepository connectionRepository;
    private final ContactAliasRepository contactAliasRepository;

    // Dangerous executable and script file extensions
    private static final Set<String> DANGEROUS_EXTENSIONS = Set.of(
            "exe", "scr", "bat", "cmd", "vbs", "vbe", "js", "jse",
            "hta", "jar", "ps1", "ps2", "msi", "msp", "pif", "com",
            "cpl", "wsf", "wsh"
    );

    // Double extension deception pattern: e.g. document.pdf.exe or invoice.docx.scr
    private static final Pattern DOUBLE_EXTENSION_PATTERN = Pattern.compile(
            ".*\\.(pdf|docx?|xlsx?|pptx?|jpg|jpeg|png|gif|txt|csv|zip|rar)\\.(" +
                    String.join("|", DANGEROUS_EXTENSIONS) + ")$",
            Pattern.CASE_INSENSITIVE
    );

    // Direct IP address in URL pattern (e.g. http://192.168.1.1/login or http://45.33.32.156/...)
    private static final Pattern IP_URL_PATTERN = Pattern.compile(
            "https?://(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?(?:/[^\\s\"'<>]*)?",
            Pattern.CASE_INSENSITIVE
    );

    // HTML anchor tag pattern: <a href="target">anchor text</a>
    private static final Pattern HTML_ANCHOR_PATTERN = Pattern.compile(
            "<a\\s+[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    // Common brand names frequently targeted for domain spoofing/phishing
    private static final Set<String> TARGETED_BRANDS = Set.of(
            "paypal", "apple", "google", "microsoft", "amazon", "netflix",
            "chase", "bankofamerica", "wellsfargo", "bnxmail", "dropbox"
    );

    // Reputable transactional Email Service Providers (ESPs) commonly used for legitimate OTP and verification emails
    private static final Set<String> KNOWN_ESP_DOMAINS = Set.of(
            "brevo.com", "sendinblue.com", "mailin.fr", "sendgrid.net", "sendgrid.com",
            "mailgun.org", "mailgun.net", "amazonses.com", "postmarkapp.com",
            "sparkpostmail.com", "mandrillapp.com"
    );

    /**
     * Main entry point: Classifies an incoming JavaMail message for a given recipient user.
     */
    public SpamClassificationResult classify(Message message, String userEmail) {
        SpamClassificationResult result = new SpamClassificationResult();
        result.setReasons(new ArrayList<>());
        if (message == null || userEmail == null || userEmail.trim().isEmpty()) {
            result.setVerdict(Verdict.INBOX);
            result.setCategory("PRIMARY");
            return result;
        }

        String normalizedUser = normalizeEmail(userEmail);

        try {
            // 1. Extract Core Metadata & Headers
            String senderRaw = extractSenderRaw(message);
            String senderEmail = normalizeEmail(extractEmailAddress(senderRaw));
            String senderDomain = extractDomain(senderEmail);
            String subject = message.getSubject() != null ? message.getSubject().trim() : "";

            // Content extraction
            String[] contents = extractContent(message);
            String plainBody = contents[0] != null ? contents[0] : "";
            String htmlBody = contents[1] != null ? contents[1] : "";

            // Attachment extraction
            List<String> attachmentNames = extractAttachmentNames(message);

            // Header extraction
            Map<String, List<String>> headers = extractHeaders(message);

            // Transactional email evaluation (OTP, verification codes, password resets, login authentication)
            boolean isTransactional = isTransactionalVerificationEmail(subject, plainBody, htmlBody, headers);
            result.setTransactional(isTransactional);

            // -------------------------------------------------------------
            // SIGNAL 1: EXPLICIT USER POLICY CHECKS (Highest Priority)
            // -------------------------------------------------------------

            // A. Check Unsubscribed Sender Policy
            // Preserves unsubscribed policy for marketing emails, but distinguishes transactional verification messages (OTP, password reset)
            boolean isUnsubscribed = isSenderUnsubscribed(normalizedUser, senderEmail, headers);
            if (isUnsubscribed) {
                if (isTransactional) {
                    log.info("SpamClassifier: Sender {} is marked unsubscribed for marketing, but message is transactional verification (OTP/security). Distinguishing from marketing per policy.", senderEmail);
                } else {
                    result.setUnsubscribed(true);
                    result.setVerdict(Verdict.SPAM);
                    result.setCategory("SPAM");
                    result.setSpamScore(1.0);
                    result.setConfidence(1.0);
                    result.getReasons().add("Sender was unsubscribed by user (routed to Spam per BNXmail policy)");
                    log.info("SpamClassifier: Sender {} is UNSUBSCRIBED by user {}", senderEmail, normalizedUser);
                    return result;
                }
            }

            // B. Check Blocked Sender Policy (Blocked senders are always Spam)
            boolean isBlocked = isSenderBlocked(normalizedUser, senderEmail);
            if (isBlocked) {
                result.setBlocked(true);
                result.setVerdict(Verdict.SPAM);
                result.setCategory("SPAM");
                result.setSpamScore(1.0);
                result.setConfidence(1.0);
                result.getReasons().add("Sender is on user's blocked list");
                log.info("SpamClassifier: Sender {} is BLOCKED by user {}", senderEmail, normalizedUser);
                return result;
            }

            // C. Check User-Reported Spam
            boolean isReportedSpam = isSenderReportedAsSpam(normalizedUser, senderEmail);
            if (isReportedSpam) {
                result.setUserReported(true);
                result.setVerdict(Verdict.SPAM);
                result.setCategory("SPAM");
                result.setSpamScore(1.0);
                result.setConfidence(1.0);
                result.getReasons().add("Sender was previously reported as spam by user");
                log.info("SpamClassifier: Sender {} was REPORTED AS SPAM by user {}", senderEmail, normalizedUser);
                return result;
            }

            // D. Check Explicitly Trusted / Whitelisted Sender (User clicked "Not Spam")
            boolean isExplicitlyTrusted = isSenderExplicitlyTrusted(normalizedUser, senderEmail);
            if (isExplicitlyTrusted) {
                result.setTrusted(true);
                // Safe pass unless confirmed weaponized attachment or confirmed spoofing
                boolean hasMaliciousAttachment = checkMaliciousAttachments(attachmentNames, result);
                boolean isSpoofed = checkDomainSpoofing(message, senderEmail, senderDomain, headers, result);

                if (!hasMaliciousAttachment && !isSpoofed) {
                    result.setVerdict(Verdict.INBOX);
                    result.setCategory(determineInboxCategory(headers, senderDomain, subject, isTransactional));
                    result.setSpamScore(0.0);
                    result.setConfidence(1.0);
                    result.getReasons().add("Sender is explicitly trusted / whitelisted by user");
                    log.info("SpamClassifier: Sender {} is TRUSTED by user {}", senderEmail, normalizedUser);
                    return result;
                }
            }

            // -------------------------------------------------------------
            // SIGNAL 2: SENDER REPUTATION & RELATIONSHIP
            // -------------------------------------------------------------
            boolean isKnownContact = isKnownOrInternalContact(normalizedUser, senderEmail, senderDomain);
            if (isKnownContact) {
                result.setTrusted(true);
            }

            boolean isFirstTime = !isKnownContact && isFirstTimeSender(normalizedUser, senderEmail);
            result.setFirstTimeSender(isFirstTime);

            // -------------------------------------------------------------
            // SIGNAL 3: MALICIOUS ATTACHMENT ANALYSIS
            // -------------------------------------------------------------
            boolean hasMaliciousAttachment = checkMaliciousAttachments(attachmentNames, result);
            if (hasMaliciousAttachment) {
                result.setHasMaliciousAttachment(true);
                result.setVerdict(Verdict.SPAM);
                result.setCategory("SPAM");
                result.setSpamScore(1.0);
                result.setConfidence(1.0);
                log.info("SpamClassifier: Malicious attachment found in email from {} for {}", senderEmail, normalizedUser);
                return result;
            }

            // -------------------------------------------------------------
            // SIGNAL 4: EMAIL AUTHENTICATION & DOMAIN SPOOFING
            // -------------------------------------------------------------
            AuthEval authEval = evaluateAuthentication(headers);
            boolean isSpoofed = checkDomainSpoofing(message, senderEmail, senderDomain, headers, result);
            if (isSpoofed) {
                result.setSpoofed(true);
                result.setVerdict(Verdict.SPAM);
                result.setCategory("SPAM");
                result.setSpamScore(1.0);
                result.setConfidence(1.0);
                log.info("SpamClassifier: Confirmed domain spoofing in email from {} for {}", senderEmail, normalizedUser);
                return result;
            }

            // -------------------------------------------------------------
            // SIGNAL 5: SUSPICIOUS & PHISHING LINK ANALYSIS
            // -------------------------------------------------------------
            LinkEval linkEval = evaluateLinks(htmlBody, plainBody, senderDomain);
            if (linkEval.isConfirmedPhishing) {
                result.setPhishing(true);
                result.setVerdict(Verdict.SPAM);
                result.setCategory("SPAM");
                result.setSpamScore(1.0);
                result.setConfidence(1.0);
                result.getReasons().addAll(linkEval.reasons);
                log.info("SpamClassifier: Confirmed phishing link detected from {} for {}", senderEmail, normalizedUser);
                return result;
            }

            // -------------------------------------------------------------
            // SIGNAL 6: CONTENT PATTERNS & BULK DETECTION
            // -------------------------------------------------------------
            ContentEval contentEval = evaluateContent(subject, plainBody, htmlBody, isTransactional);
            boolean hasUnsubscribeHeader = headers.containsKey("list-unsubscribe");
            boolean isPrecedenceBulk = isBulkHeaderPresent(headers);

            // -------------------------------------------------------------
            // SCORING ENGINE: MULTI-SIGNAL AGGREGATION
            // -------------------------------------------------------------
            double spamScore = 0.0;

            // Auth scoring: Only apply if actual auth headers exist
            if (authEval.hasAuthHeaders) {
                if (authEval.dmarcFailed || (authEval.spfFailed && authEval.dkimFailed)) {
                    spamScore += 0.50;
                    result.getReasons().add("Authentication failure: DMARC or SPF+DKIM failed");
                } else if (authEval.spfFailed || authEval.dkimFailed) {
                    spamScore += isFirstTime ? 0.40 : 0.25;
                    result.getReasons().add("Authentication warning: partial SPF/DKIM failure");
                } else if (authEval.spfPassed && (authEval.dkimPassed || authEval.dmarcPassed)) {
                    spamScore -= 0.20; // Trust credit for cryptographically verified identity
                }
            }

            // Link risk
            if (linkEval.hasIpUrl) {
                spamScore += 0.40;
                result.getReasons().add("Suspicious raw IP address link in email");
            }
            if (linkEval.suspiciousCount > 0) {
                spamScore += Math.min(0.35, linkEval.suspiciousCount * 0.15);
                result.getReasons().addAll(linkEval.reasons);
            }

            // Content risk (Multi-token phishing/scam patterns)
            if (contentEval.isCredentialHarvestingAttempt) {
                spamScore += 0.45;
                result.getReasons().add("Credential harvesting language detected");
            }
            if (contentEval.isUrgentFinancialScam) {
                spamScore += 0.35;
                result.getReasons().add("Urgent financial/payment scam pattern");
            }
            if (contentEval.isFakeLotteryOrPrize) {
                spamScore += 0.40;
                result.getReasons().add("Lottery/prize scam pattern");
            }

            // Bulk & sender characteristics
            if (isPrecedenceBulk || hasUnsubscribeHeader) {
                if (isTransactional) {
                    // Transactional verification emails sent via third-party ESPs (e.g. Brevo)
                    // often carry unsubscribe or bulk headers; do not penalize as unsolicited marketing bulk
                    log.debug("SpamClassifier: Transactional verification message carries bulk/unsubscribe headers, skipping bulk penalty");
                } else if (authEval.hasAuthHeaders && (authEval.spfPassed || authEval.dkimPassed) && spamScore < 0.35) {
                    // Legitimate newsletter / promotional email
                    spamScore -= 0.15;
                } else if (isFirstTime && spamScore >= 0.25) {
                    spamScore += 0.20;
                    result.getReasons().add("Unsolicited bulk email from unknown sender");
                }
            }

            // Relationship adjustments
            if (isKnownContact) {
                spamScore = Math.max(0.0, spamScore - 0.40);
            } else if (isFirstTime) {
                if (isTransactional) {
                    // Legitimate first-time verification emails are expected when signing up for a new service
                    // Do not apply first-time penalty if auth and content are clean
                } else if (spamScore > 0.30) {
                    spamScore += 0.15;
                    result.getReasons().add("Suspicious indicators on first-time sender");
                }
            }

            // Clamp score between 0.0 and 1.0
            spamScore = Math.max(0.0, Math.min(1.0, spamScore));
            result.setSpamScore(spamScore);

            // -------------------------------------------------------------
            // FINAL VERDICT & DESTINATION ROUTING
            // -------------------------------------------------------------
            if (isKnownContact && spamScore < 0.70) {
                result.setVerdict(Verdict.INBOX);
                result.setCategory(determineInboxCategory(headers, senderDomain, subject, isTransactional));
                result.setConfidence(0.95);
            } else if (isTransactional && spamScore < 0.50 && !linkEval.isConfirmedPhishing && !isSpoofed && !hasMaliciousAttachment) {
                // Legitimate transactional verification email -> Inbox
                result.setVerdict(Verdict.INBOX);
                result.setCategory("UPDATES");
                result.setConfidence(0.95);
                result.getReasons().add("Legitimate transactional verification email routed to Inbox");
                log.info("SpamClassifier: Legitimate transactional verification email from {} routed to INBOX", senderEmail);
            } else if (isFirstTime && spamScore >= 0.35 && spamScore < 0.70) {
                // Suspicious first-time sender -> Review
                result.setVerdict(Verdict.REVIEW);
                result.setCategory("REVIEW");
                result.setConfidence(0.70);
                result.getReasons().add("Suspicious first-time sender routed to Review for verification");
            } else if (spamScore >= 0.70) {
                // High confidence spam
                result.setVerdict(Verdict.SPAM);
                result.setCategory("SPAM");
                result.setConfidence(Math.min(1.0, 0.5 + (spamScore * 0.5)));
            } else if (spamScore >= 0.35) {
                // Uncertain classification -> Review
                result.setVerdict(Verdict.REVIEW);
                result.setCategory("REVIEW");
                result.setConfidence(0.60);
                result.getReasons().add("Uncertain classification routed to Review");
            } else {
                // Clean / Legitimate -> Inbox
                result.setVerdict(Verdict.INBOX);
                result.setCategory(determineInboxCategory(headers, senderDomain, subject, isTransactional));
                result.setConfidence(0.90);
            }

        } catch (Exception e) {
            log.error("SpamClassifier: Error during classification for user {}: {}", userEmail, e.getMessage(), e);
            // Fail-safe: Never lose emails when classification fails
            result.setVerdict(Verdict.INBOX);
            result.setCategory("PRIMARY");
            result.setSpamScore(0.0);
            result.getReasons().add("Classification fallback due to processing notice: " + e.getMessage());
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // SENDER REPUTATION & PREFERENCE MANAGEMENT
    // -------------------------------------------------------------------------

    @Transactional
    public void recordSenderReputation(String userEmail, String senderEmail, String status, String notes) {
        String u = normalizeEmail(userEmail);
        String s = normalizeEmail(senderEmail);
        if (u == null || s == null) return;

        try {
            Optional<SenderReputation> existing = senderReputationRepository.findByUserEmailAndSenderEmail(u, s);
            SenderReputation rep = existing.orElseGet(() -> SenderReputation.builder()
                    .userEmail(u)
                    .senderEmail(s)
                    .build());
            rep.setStatus(status);
            rep.setNotes(notes);
            rep.setUpdatedAt(LocalDateTime.now());
            senderReputationRepository.save(rep);
            log.info("SpamClassifier: Updated reputation for user '{}' sender '{}' to '{}'", u, s, status);
        } catch (Exception e) {
            log.warn("SpamClassifier: Failed to record sender reputation: {}", e.getMessage());
        }
    }

    /**
     * Called when user restores an email from Spam ("Not Spam" / restoreFromSpam).
     * Whitelists the sender so future emails avoid spam classification.
     */
    @Transactional
    public void markSenderAsNotSpam(String userEmail, String senderEmail) {
        String u = normalizeEmail(userEmail);
        String s = normalizeEmail(senderEmail);
        if (u == null || s == null) return;

        log.info("SpamClassifier: Marking sender '{}' as NOT SPAM (TRUSTED) for user '{}'", s, u);
        recordSenderReputation(u, s, "TRUSTED", "User marked email as Not Spam");

        // Remove from blocked list if present
        try {
            blockedContactRepository.deleteByUserEmailAndBlockedEmail(u, s);
        } catch (Exception ignored) {}

        // Remove reports from this user for this sender if present
        try {
            mailAccountRepository.findByEmail(s).ifPresent(account -> {
                reportRepository.deleteByReportedUserId(account.getUserId());
            });
        } catch (Exception ignored) {}
    }

    public boolean isSenderExplicitlyTrusted(String userEmail, String senderEmail) {
        if (userEmail == null || senderEmail == null) return false;
        return senderReputationRepository.findByUserEmailAndSenderEmail(userEmail, senderEmail)
                .map(r -> "TRUSTED".equalsIgnoreCase(r.getStatus()) || "NOT_SPAM".equalsIgnoreCase(r.getStatus()))
                .orElse(false);
    }

    public boolean isSenderReportedAsSpam(String userEmail, String senderEmail) {
        if (userEmail == null || senderEmail == null) return false;
        // Check reputation table
        boolean inRep = senderReputationRepository.findByUserEmailAndSenderEmail(userEmail, senderEmail)
                .map(r -> "REPORTED_SPAM".equalsIgnoreCase(r.getStatus()))
                .orElse(false);
        if (inRep) return true;

        // Check abuse reports table
        try {
            return mailAccountRepository.findByEmail(senderEmail)
                    .map(acc -> reportRepository.countByReportedUserId(acc.getUserId()) > 0)
                    .orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isSenderBlocked(String userEmail, String senderEmail) {
        if (userEmail == null || senderEmail == null) return false;
        // Check reputation status first
        Optional<SenderReputation> rep = senderReputationRepository.findByUserEmailAndSenderEmail(userEmail, senderEmail);
        if (rep.isPresent()) {
            if ("BLOCKED".equalsIgnoreCase(rep.get().getStatus())) return true;
            if ("UNSUBSCRIBED".equalsIgnoreCase(rep.get().getStatus())) return false;
        }
        return blockedContactRepository.existsByUserEmailAndBlockedEmail(userEmail, senderEmail);
    }

    public boolean isSenderUnsubscribed(String userEmail, String senderEmail, Map<String, List<String>> headers) {
        if (userEmail == null || senderEmail == null) return false;
        // 1. Check explicit reputation status
        boolean inRep = senderReputationRepository.findByUserEmailAndSenderEmail(userEmail, senderEmail)
                .map(r -> "UNSUBSCRIBED".equalsIgnoreCase(r.getStatus()))
                .orElse(false);
        if (inRep) return true;

        // 2. Check if sender has List-Unsubscribe header or was marked unsubscribed in BlockedContact
        if (blockedContactRepository.existsByUserEmailAndBlockedEmail(userEmail, senderEmail)) {
            if (headers != null && (headers.containsKey("list-unsubscribe") || isBulkHeaderPresent(headers))) {
                return true;
            }
        }
        return false;
    }

    public boolean isSenderUnsubscribed(String userEmail, String senderEmail) {
        return isSenderUnsubscribed(userEmail, senderEmail, null);
    }

    public boolean isKnownOrInternalContact(String userEmail, String senderEmail, String senderDomain) {
        if (userEmail == null || senderEmail == null) return false;

        // Self-sent email
        if (userEmail.equalsIgnoreCase(senderEmail)) return true;

        // Internal system account
        try {
            if (mailAccountRepository.existsByEmail(senderEmail)) {
                return true;
            }
        } catch (Exception ignored) {}

        // User connections / contacts
        try {
            Optional<MailAccount> userAcc = mailAccountRepository.findByEmail(userEmail);
            Optional<MailAccount> senderAcc = mailAccountRepository.findByEmail(senderEmail);
            if (userAcc.isPresent() && senderAcc.isPresent()) {
                Long u1 = userAcc.get().getUserId();
                Long u2 = senderAcc.get().getUserId();
                if (connectionRepository.findConnectionBetweenUsers(u1, u2).isPresent()) {
                    return true;
                }
                if (contactAliasRepository.findByOwnerUserIdAndContactUserId(u1, u2).isPresent()) {
                    return true;
                }
            }
        } catch (Exception ignored) {}

        return false;
    }

    public boolean isFirstTimeSender(String userEmail, String senderEmail) {
        if (userEmail == null || senderEmail == null) return true;
        return !senderReputationRepository.existsByUserEmailAndSenderEmail(userEmail, senderEmail);
    }

    // -------------------------------------------------------------------------
    // MALICIOUS ATTACHMENT CHECK
    // -------------------------------------------------------------------------

    private boolean checkMaliciousAttachments(List<String> attachmentNames, SpamClassificationResult result) {
        if (attachmentNames == null || attachmentNames.isEmpty()) return false;

        for (String name : attachmentNames) {
            if (name == null || name.trim().isEmpty()) continue;
            String lower = name.trim().toLowerCase();

            // 1. Direct dangerous extension check
            int dotIdx = lower.lastIndexOf('.');
            if (dotIdx > 0 && dotIdx < lower.length() - 1) {
                String ext = lower.substring(dotIdx + 1);
                if (DANGEROUS_EXTENSIONS.contains(ext)) {
                    result.getReasons().add("Dangerous executable attachment detected: " + name);
                    return true;
                }
            }

            // 2. Double extension deception check (e.g. invoice.pdf.exe)
            if (DOUBLE_EXTENSION_PATTERN.matcher(lower).matches()) {
                result.getReasons().add("Deceptive double-extension attachment detected: " + name);
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // AUTHENTICATION & DOMAIN SPOOFING CHECK
    // -------------------------------------------------------------------------

    private record AuthEval(
            boolean hasAuthHeaders,
            boolean spfPassed,
            boolean spfFailed,
            boolean dkimPassed,
            boolean dkimFailed,
            boolean dmarcPassed,
            boolean dmarcFailed
    ) {}

    private AuthEval evaluateAuthentication(Map<String, List<String>> headers) {
        boolean hasAuth = false;
        boolean spfPass = false, spfFail = false;
        boolean dkimPass = false, dkimFail = false;
        boolean dmarcPass = false, dmarcFail = false;

        List<String> authResults = headers.getOrDefault("authentication-results", Collections.emptyList());
        List<String> recSpf = headers.getOrDefault("received-spf", Collections.emptyList());
        List<String> arcResults = headers.getOrDefault("arc-authentication-results", Collections.emptyList());

        List<String> combined = new ArrayList<>();
        combined.addAll(authResults);
        combined.addAll(recSpf);
        combined.addAll(arcResults);

        if (!combined.isEmpty()) {
            hasAuth = true;
            for (String h : combined) {
                String lower = h.toLowerCase();
                if (lower.contains("spf=pass") || lower.startsWith("pass")) spfPass = true;
                if (lower.contains("spf=fail") || lower.startsWith("fail")) spfFail = true;

                if (lower.contains("dkim=pass")) dkimPass = true;
                if (lower.contains("dkim=fail")) dkimFail = true;

                if (lower.contains("dmarc=pass")) dmarcPass = true;
                if (lower.contains("dmarc=fail") || lower.contains("dmarc=reject")) dmarcFail = true;
            }
        }

        return new AuthEval(hasAuth, spfPass, spfFail, dkimPass, dkimFail, dmarcPass, dmarcFail);
    }

    private boolean checkDomainSpoofing(Message message, String senderEmail, String senderDomain,
                                        Map<String, List<String>> headers, SpamClassificationResult result) {
        if (senderDomain == null || senderDomain.isEmpty()) return false;

        // Check if From header displays a prominent brand or local domain
        boolean claimsBrand = false;
        for (String brand : TARGETED_BRANDS) {
            if (senderDomain.equals(brand + ".com") || senderDomain.endsWith("." + brand + ".com")) {
                claimsBrand = true;
                break;
            }
        }

        // Compare with Return-Path and Sender header
        List<String> returnPaths = headers.getOrDefault("return-path", Collections.emptyList());
        List<String> senderHeaders = headers.getOrDefault("sender", Collections.emptyList());

        String envelopeDomain = null;
        if (!returnPaths.isEmpty()) {
            String clean = extractEmailAddress(returnPaths.get(0));
            envelopeDomain = extractDomain(clean);
        } else if (!senderHeaders.isEmpty()) {
            String clean = extractEmailAddress(senderHeaders.get(0));
            envelopeDomain = extractDomain(clean);
        }

        // If envelope domain is present and does not match From domain
        if (envelopeDomain != null && !envelopeDomain.equalsIgnoreCase(senderDomain)) {
            // Check auth
            AuthEval auth = evaluateAuthentication(headers);

            final String envDomain = envelopeDomain;
            boolean isKnownEsp = KNOWN_ESP_DOMAINS.stream()
                    .anyMatch(esp -> envDomain.equalsIgnoreCase(esp) || envDomain.endsWith("." + esp));

            if (claimsBrand && (auth.spfFailed || auth.dkimFailed || auth.dmarcFailed)) {
                result.getReasons().add("Domain spoofing: Claims " + senderDomain + " but envelope is " + envelopeDomain + " with auth failure");
                return true;
            }
            if (auth.dmarcFailed && !isKnownEsp) {
                result.getReasons().add("Domain spoofing: DMARC failed and domain mismatch");
                return true;
            }
        }

        return false;
    }

    // -------------------------------------------------------------------------
    // LINK ANALYSIS
    // -------------------------------------------------------------------------

    private static class LinkEval {
        boolean isConfirmedPhishing = false;
        boolean hasIpUrl = false;
        int suspiciousCount = 0;
        List<String> reasons = new ArrayList<>();
    }

    private LinkEval evaluateLinks(String htmlBody, String plainBody, String senderDomain) {
        LinkEval eval = new LinkEval();
        String combined = (htmlBody != null ? htmlBody : "") + " " + (plainBody != null ? plainBody : "");

        // 1. Check for raw IP address URLs
        Matcher ipMatcher = IP_URL_PATTERN.matcher(combined);
        if (ipMatcher.find()) {
            eval.hasIpUrl = true;
            eval.reasons.add("Found URL pointing to raw IP address: " + ipMatcher.group());
            // If raw IP has login/verify/banking path -> confirmed phishing
            String matched = ipMatcher.group().toLowerCase();
            if (matched.contains("login") || matched.contains("signin") || matched.contains("verify") ||
                matched.contains("bank") || matched.contains("account") || matched.contains("update")) {
                eval.isConfirmedPhishing = true;
                eval.reasons.add("Confirmed phishing: Direct IP URL targeting credential/account theft: " + matched);
                return eval;
            }
        }

        // 2. Check HTML anchor tags for misleading hyperlinks (anchor text != target href)
        if (htmlBody != null && !htmlBody.isEmpty()) {
            Matcher anchorMatcher = HTML_ANCHOR_PATTERN.matcher(htmlBody);
            while (anchorMatcher.find()) {
                String href = anchorMatcher.group(1).trim();
                String text = anchorMatcher.group(2).replaceAll("<[^>]*>", "").trim();

                String hrefDomain = extractDomainFromUrl(href);
                String textDomain = extractDomainFromUrl(text);

                // If anchor text visually displays a domain URL (e.g. "https://paypal.com/signin")
                // but the href actually points to a different domain (e.g. "http://evil-site.com")
                if (textDomain != null && hrefDomain != null && !textDomain.equalsIgnoreCase(hrefDomain)) {
                    final String hDomain = hrefDomain;
                    boolean isEspTrackingLink = KNOWN_ESP_DOMAINS.stream()
                            .anyMatch(esp -> hDomain.equalsIgnoreCase(esp) || hDomain.endsWith("." + esp));

                    // Only skip phishing flag if href is a legitimate ESP tracking service AND textDomain is not a spoofed targeted brand
                    boolean isTargetedBrandSpoof = false;
                    for (String brand : TARGETED_BRANDS) {
                        if (textDomain.contains(brand)) {
                            isTargetedBrandSpoof = true;
                            break;
                        }
                    }

                    if (!isEspTrackingLink || isTargetedBrandSpoof) {
                        eval.isConfirmedPhishing = true;
                        eval.reasons.add("Confirmed phishing: Misleading link displays '" + textDomain + "' but navigates to '" + hrefDomain + "'");
                        return eval;
                    }
                }

                // Check for suspicious brand lookalike domains in href
                if (hrefDomain != null) {
                    for (String brand : TARGETED_BRANDS) {
                        if (hrefDomain.contains(brand) && !hrefDomain.equals(brand + ".com") && !hrefDomain.endsWith("." + brand + ".com")) {
                            eval.suspiciousCount++;
                            eval.reasons.add("Suspicious lookalike brand domain in link: " + hrefDomain);
                        }
                    }
                }
            }
        }

        return eval;
    }

    // -------------------------------------------------------------------------
    // CONTENT ANALYSIS
    // -------------------------------------------------------------------------

    private record ContentEval(
            boolean isCredentialHarvestingAttempt,
            boolean isUrgentFinancialScam,
            boolean isFakeLotteryOrPrize
    ) {}

    private ContentEval evaluateContent(String subject, String plainBody, String htmlBody, boolean isTransactional) {
        String fullText = ((subject != null ? subject : "") + " " +
                (plainBody != null ? plainBody : "")).toLowerCase();

        boolean coercionThreat = fullText.contains("account suspended") ||
                fullText.contains("suspended within") ||
                fullText.contains("terminated unless") ||
                fullText.contains("unauthorized access detected") ||
                fullText.contains("security breach on your account") ||
                fullText.contains("account will be closed");

        boolean credentialDemand = fullText.contains("enter your password") ||
                fullText.contains("confirm your password") ||
                fullText.contains("verify your banking") ||
                fullText.contains("enter your pin and password") ||
                fullText.contains("social security") ||
                fullText.contains("credit card details");

        boolean cred;
        if (isTransactional) {
            // For transactional emails, only flag if there is active phishing coercion combined with credential harvesting demands
            cred = coercionThreat && credentialDemand;
        } else {
            // Non-transactional emails asking for credentials under threat/coercion
            cred = (coercionThreat && (fullText.contains("password") || fullText.contains("passcode"))) ||
                    (coercionThreat && credentialDemand);
        }

        boolean fin = (fullText.contains("wire transfer") || fullText.contains("bitcoin payment") ||
                       fullText.contains("gift card") || fullText.contains("crypto wallet")) &&
                (fullText.contains("urgently") || fullText.contains("immediately") || fullText.contains("overdue invoice"));

        boolean prize = (fullText.contains("lottery") || fullText.contains("jackpot") || fullText.contains("inheritance")) &&
                (fullText.contains("claim your") || fullText.contains("you have won") || fullText.contains("million dollar"));

        return new ContentEval(cred, fin, prize);
    }

    private ContentEval evaluateContent(String subject, String plainBody, String htmlBody) {
        return evaluateContent(subject, plainBody, htmlBody, false);
    }

    // -------------------------------------------------------------------------
    // CATEGORY DETERMINATION
    // -------------------------------------------------------------------------

    private String determineInboxCategory(Map<String, List<String>> headers, String senderDomain, String subject, boolean isTransactional) {
        if (isTransactional) {
            return "UPDATES";
        }
        String sub = subject != null ? subject.toUpperCase() : "";
        String domain = senderDomain != null ? senderDomain.toLowerCase() : "";

        // Check if promotional / newsletter
        if (headers.containsKey("list-unsubscribe") || isBulkHeaderPresent(headers) ||
            domain.contains("mailchimp") || domain.contains("sendgrid") || domain.contains("hubspot") ||
            domain.contains("constantcontact") || sub.contains("NEWSLETTER") || sub.contains("WEEKLY DIGEST")) {
            return "PROMOTIONS";
        }

        // Check if Social
        if (domain.contains("linkedin.com") || domain.contains("twitter.com") || domain.contains("x.com") ||
            domain.contains("facebookmail.com") || domain.contains("instagram.com") || domain.contains("youtube.com") ||
            domain.contains("reddit.com") || domain.contains("discord.com") || domain.contains("slack.com")) {
            return "SOCIAL";
        }

        // Check if Job
        if (domain.contains("indeed.com") || domain.contains("glassdoor.com") || domain.contains("naukri.com") ||
            domain.contains("greenhouse.io") || domain.contains("lever.co") || sub.contains("JOB APPLICATION") ||
            sub.contains("INTERVIEW") || sub.contains("OFFER LETTER") || sub.contains("HIRING")) {
            return "JOB";
        }

        // Check if System Updates
        if (headers.containsKey("auto-submitted") || domain.contains("github.com") || domain.contains("gitlab.com") ||
            domain.contains("jira") || sub.contains("OTP") || sub.contains("VERIFICATION CODE") ||
            sub.contains("SECURITY ALERT") || sub.contains("RECEIPT") || sub.contains("INVOICE")) {
            return "UPDATES";
        }

        return "PRIMARY";
    }

    private String determineInboxCategory(Map<String, List<String>> headers, String senderDomain, String subject) {
        return determineInboxCategory(headers, senderDomain, subject, false);
    }

    private boolean isBulkHeaderPresent(Map<String, List<String>> headers) {
        List<String> precedence = headers.getOrDefault("precedence", Collections.emptyList());
        for (String p : precedence) {
            String lower = p.toLowerCase();
            if (lower.contains("bulk") || lower.contains("list") || lower.contains("junk")) return true;
        }
        return false;
    }

    /**
     * Identifies legitimate transactional emails including OTPs, account verification codes,
     * Welcome emails, registration confirmations, password resets, login authentication codes,
     * transaction confirmations, and important security alerts.
     */
    public boolean isTransactionalVerificationEmail(String subject, String plainBody, String htmlBody, Map<String, List<String>> headers) {
        String sub = subject != null ? subject.toLowerCase() : "";
        String plain = plainBody != null ? plainBody.toLowerCase() : "";
        String html = htmlBody != null ? htmlBody.toLowerCase() : "";
        String combined = (sub + " " + plain + " " + html);

        // Subject keywords for transactional emails
        boolean subMatches = sub.contains("otp") ||
                sub.contains("one-time password") ||
                sub.contains("one time password") ||
                sub.contains("one-time passcode") ||
                sub.contains("one time passcode") ||
                sub.contains("verification code") ||
                sub.contains("verify your") ||
                sub.contains("verify email") ||
                sub.contains("email verification") ||
                sub.contains("account verification") ||
                sub.contains("confirm your email") ||
                sub.contains("email confirmation") ||
                sub.contains("confirm your account") ||
                sub.contains("activate your account") ||
                sub.contains("welcome to") ||
                sub.contains("welcome aboard") ||
                sub.contains("welcome!") ||
                sub.contains("welcome,") ||
                sub.contains("account created") ||
                sub.contains("registration confirmed") ||
                sub.contains("registration confirmation") ||
                sub.contains("registration complete") ||
                sub.contains("getting started with") ||
                sub.contains("password reset") ||
                sub.contains("reset your password") ||
                sub.contains("reset password") ||
                sub.contains("password recovery") ||
                sub.contains("login code") ||
                sub.contains("login verification") ||
                sub.contains("sign-in code") ||
                sub.contains("signin code") ||
                sub.contains("security code") ||
                sub.contains("passcode") ||
                sub.contains("2-step verification") ||
                sub.contains("two-factor") ||
                sub.contains("2-factor") ||
                sub.contains("2fa") ||
                sub.contains("security alert") ||
                sub.contains("security notification") ||
                sub.contains("temporary password") ||
                sub.contains("temporary passcode") ||
                sub.contains("order confirmation") ||
                sub.contains("order confirmed") ||
                sub.contains("payment received") ||
                sub.contains("payment confirmation") ||
                sub.contains("payment receipt") ||
                sub.contains("subscription confirmed") ||
                sub.contains("transaction confirmation") ||
                sub.contains("purchase receipt") ||
                sub.contains("billing receipt") ||
                sub.contains("free trial") ||
                sub.contains("cyberletics");

        // Body phrases for transactional emails
        boolean bodyMatches = combined.contains("verification code") ||
                combined.contains("verification pin") ||
                combined.contains("verify your email") ||
                combined.contains("verify your account") ||
                combined.contains("confirm your email") ||
                combined.contains("confirm your account") ||
                combined.contains("email verification") ||
                combined.contains("account verification") ||
                combined.contains("one-time password") ||
                combined.contains("one-time passcode") ||
                combined.contains("one time password") ||
                combined.contains("one time passcode") ||
                combined.contains("welcome to") ||
                combined.contains("welcome aboard") ||
                combined.contains("your account has been created") ||
                combined.contains("registration confirmed") ||
                combined.contains("registration is complete") ||
                combined.contains("account has been activated") ||
                combined.contains("order confirmation") ||
                combined.contains("payment confirmation") ||
                combined.contains("payment receipt") ||
                combined.contains("transaction confirmation") ||
                combined.contains("subscription confirmed") ||
                combined.contains("free trial has started") ||
                combined.contains("password reset") ||
                combined.contains("reset your password") ||
                combined.contains("reset password") ||
                combined.contains("login code") ||
                combined.contains("login verification") ||
                combined.contains("sign-in code") ||
                combined.contains("security code") ||
                combined.contains("temporary password") ||
                combined.contains("temporary passcode") ||
                combined.contains("two-factor authentication") ||
                combined.contains("2-factor authentication") ||
                combined.contains("cyberletics verification") ||
                (combined.contains("otp") && (combined.contains("code") || combined.contains("enter") || combined.contains("login") || combined.contains("verify") || combined.contains("password") || combined.contains("account")));

        // Numeric OTP or verification/activation/confirmation link in text
        boolean hasCodeToken = Pattern.compile("\\b\\d{4,8}\\b").matcher(combined).find() ||
                combined.contains("verify-account") ||
                combined.contains("reset-password") ||
                combined.contains("verify-email") ||
                combined.contains("confirm-email") ||
                combined.contains("click here to verify") ||
                combined.contains("click the link to verify") ||
                combined.contains("get started") ||
                combined.contains("view your order") ||
                combined.contains("view receipt") ||
                combined.contains("dashboard");

        return subMatches || (bodyMatches && hasCodeToken);
    }

    // -------------------------------------------------------------------------
    // UTILITIES
    // -------------------------------------------------------------------------

    public String normalizeEmail(String email) {
        if (email == null) return null;
        return email.trim().toLowerCase();
    }

    public String extractEmailAddress(String fromHeader) {
        if (fromHeader == null) return null;
        if (fromHeader.contains("<") && fromHeader.contains(">")) {
            return fromHeader.substring(fromHeader.indexOf("<") + 1, fromHeader.indexOf(">")).trim();
        }
        return fromHeader.trim();
    }

    public String extractDomain(String email) {
        if (email == null || !email.contains("@")) return null;
        String[] parts = email.split("@");
        return parts.length == 2 ? parts[1].toLowerCase().trim() : null;
    }

    public String extractDomainFromUrl(String url) {
        if (url == null) return null;
        String clean = url.trim().toLowerCase();
        if (clean.startsWith("http://") || clean.startsWith("https://")) {
            int start = clean.indexOf("://") + 3;
            int end = clean.indexOf('/', start);
            if (end == -1) end = clean.indexOf('?', start);
            if (end == -1) end = clean.length();
            String host = clean.substring(start, end);
            if (host.contains(":")) host = host.substring(0, host.indexOf(':'));
            return host;
        }
        if (clean.contains(".") && !clean.contains(" ") && !clean.contains("@")) {
            int slash = clean.indexOf('/');
            return slash > 0 ? clean.substring(0, slash) : clean;
        }
        return null;
    }

    private String extractSenderRaw(Message message) {
        try {
            Address[] from = message.getFrom();
            if (from != null && from.length > 0) return from[0].toString();
        } catch (Exception ignored) {}
        return "";
    }

    private Map<String, List<String>> extractHeaders(Message message) {
        Map<String, List<String>> map = new HashMap<>();
        try {
            Enumeration<Header> headers = message.getAllHeaders();
            while (headers.hasMoreElements()) {
                Header h = headers.nextElement();
                String name = h.getName().toLowerCase();
                map.computeIfAbsent(name, k -> new ArrayList<>()).add(h.getValue());
            }
        } catch (Exception ignored) {}
        return map;
    }

    private List<String> extractAttachmentNames(Part part) {
        List<String> names = new ArrayList<>();
        try {
            if (part.isMimeType("multipart/*")) {
                Multipart mp = (Multipart) part.getContent();
                for (int i = 0; i < mp.getCount(); i++) {
                    names.addAll(extractAttachmentNames(mp.getBodyPart(i)));
                }
            } else {
                String name = part.getFileName();
                if (name != null && !name.trim().isEmpty()) {
                    names.add(MimeUtility.decodeText(name).trim().replaceAll("^\"|\"$", ""));
                }
            }
        } catch (Exception ignored) {}
        return names;
    }

    private String[] extractContent(Message message) {
        String plain = "";
        String html = "";
        try {
            Object content = message.getContent();
            if (content instanceof String) {
                if (message.isMimeType("text/html")) html = (String) content;
                else plain = (String) content;
            } else if (content instanceof Multipart mp) {
                for (int i = 0; i < mp.getCount(); i++) {
                    BodyPart bp = mp.getBodyPart(i);
                    if (bp.isMimeType("text/plain")) plain = (String) bp.getContent();
                    else if (bp.isMimeType("text/html")) html = (String) bp.getContent();
                    else if (bp.getContent() instanceof Multipart nested) {
                        for (int j = 0; j < nested.getCount(); j++) {
                            BodyPart nb = nested.getBodyPart(j);
                            if (nb.isMimeType("text/plain")) plain = (String) nb.getContent();
                            else if (nb.isMimeType("text/html")) html = (String) nb.getContent();
                        }
                    }
                }
            }
            if (plain.isEmpty() && !html.isEmpty()) {
                plain = html.replaceAll("<[^>]*>", " ").trim();
            }
        } catch (Exception ignored) {}
        return new String[]{plain, html};
    }
}
