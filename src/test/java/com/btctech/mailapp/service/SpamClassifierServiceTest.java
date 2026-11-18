package com.btctech.mailapp.service;

import com.btctech.mailapp.dto.SpamClassificationResult;
import com.btctech.mailapp.dto.SpamClassificationResult.Verdict;
import com.btctech.mailapp.entity.MailAccount;
import com.btctech.mailapp.entity.SenderReputation;
import com.btctech.mailapp.repository.*;
import jakarta.mail.Address;
import jakarta.mail.Header;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.BodyPart;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class SpamClassifierServiceTest {

    private BlockedContactRepository blockedContactRepository;
    private SenderReputationRepository senderReputationRepository;
    private ReportRepository reportRepository;
    private MailAccountRepository mailAccountRepository;
    private ConnectionRepository connectionRepository;
    private ContactAliasRepository contactAliasRepository;

    private SpamClassifierService spamClassifierService;

    private final String recipientUser = "user@bnxmail.com";

    @BeforeEach
    void setUp() {
        blockedContactRepository = mock(BlockedContactRepository.class);
        senderReputationRepository = mock(SenderReputationRepository.class);
        reportRepository = mock(ReportRepository.class);
        mailAccountRepository = mock(MailAccountRepository.class);
        connectionRepository = mock(ConnectionRepository.class);
        contactAliasRepository = mock(ContactAliasRepository.class);

        spamClassifierService = new SpamClassifierService(
                blockedContactRepository,
                senderReputationRepository,
                reportRepository,
                mailAccountRepository,
                connectionRepository,
                contactAliasRepository
        );
    }

    private Message createMockMessage(String from, String subject, String body, String htmlBody,
                                      Map<String, String> headers, List<String> attachmentFilenames) throws Exception {
        Message message = mock(Message.class);

        if (from != null) {
            when(message.getFrom()).thenReturn(new Address[]{new InternetAddress(from)});
        }
        when(message.getSubject()).thenReturn(subject);

        // Headers
        List<Header> headerList = new ArrayList<>();
        if (headers != null) {
            headers.forEach((k, v) -> {
                headerList.add(new Header(k, v));
                try {
                    when(message.getHeader(k)).thenReturn(new String[]{v});
                } catch (Exception ignored) {}
            });
        }
        when(message.getAllHeaders()).thenReturn(Collections.enumeration(headerList));

        // Content
        if (htmlBody != null && !htmlBody.isEmpty()) {
            when(message.getContent()).thenReturn(htmlBody);
            when(message.isMimeType("text/html")).thenReturn(true);
        } else if (body != null) {
            when(message.getContent()).thenReturn(body);
            when(message.isMimeType("text/plain")).thenReturn(true);
        } else {
            when(message.getContent()).thenReturn("");
        }

        // Attachments
        if (attachmentFilenames != null && !attachmentFilenames.isEmpty()) {
            Multipart multipart = mock(Multipart.class);
            when(message.isMimeType("multipart/*")).thenReturn(true);
            when(message.getContent()).thenReturn(multipart);
            when(multipart.getCount()).thenReturn(attachmentFilenames.size());

            for (int i = 0; i < attachmentFilenames.size(); i++) {
                BodyPart bp = mock(BodyPart.class);
                when(bp.getFileName()).thenReturn(attachmentFilenames.get(i));
                when(bp.isMimeType("multipart/*")).thenReturn(false);
                when(multipart.getBodyPart(i)).thenReturn(bp);
            }
        }

        return message;
    }

    @Test
    @DisplayName("Scenario 1: Known trusted sender → Inbox")
    void testScenario1_KnownTrustedSender_RoutesToInbox() throws Exception {
        String trustedSender = "colleague@bnxmail.com";
        Message msg = createMockMessage(trustedSender, "Project Sync Tomorrow",
                "Hi, let us meet tomorrow at 10 AM to discuss the roadmap.", null, null, null);

        // Mark as internal mail account
        when(mailAccountRepository.existsByEmail(trustedSender)).thenReturn(true);

        SpamClassificationResult result = spamClassifierService.classify(msg, recipientUser);

        assertEquals(Verdict.INBOX, result.getVerdict());
        assertTrue(result.isTrusted());
        assertEquals("PRIMARY", result.getCategory());
        assertTrue(result.getSpamScore() < 0.35);
    }

    @Test
    @DisplayName("Scenario 2: New legitimate sender → Inbox")
    void testScenario2_NewLegitimateSender_RoutesToInbox() throws Exception {
        String newSender = "partner.contact@newbusinesscorp.com";
        Message msg = createMockMessage(newSender, "Partnership Proposal Introduction",
                "Dear team, please find our business collaboration proposal for Q1.", null, null, null);

        // Unknown sender (not in reputation repo, not in contacts)
        when(senderReputationRepository.existsByUserEmailAndSenderEmail(anyString(), anyString())).thenReturn(false);

        SpamClassificationResult result = spamClassifierService.classify(msg, recipientUser);

        assertEquals(Verdict.INBOX, result.getVerdict());
        assertTrue(result.isFirstTimeSender());
        assertEquals("PRIMARY", result.getCategory());
        assertTrue(result.getSpamScore() < 0.35);
    }

    @Test
    @DisplayName("Scenario 3: Subscribed sender → Inbox")
    void testScenario3_SubscribedSender_RoutesToInbox() throws Exception {
        String subscribedSender = "newsletter@techinsights.com";
        Map<String, String> headers = Map.of(
                "List-Unsubscribe", "<https://techinsights.com/unsub?id=123>",
                "Precedence", "bulk",
                "Authentication-Results", "mx.bnxmail.com; spf=pass; dkim=pass; dmarc=pass"
        );
        Message msg = createMockMessage(subscribedSender, "Tech Insights Weekly #42",
                "Here are the top engineering stories this week.", null, headers, null);

        // Not unsubscribed, not blocked
        when(blockedContactRepository.existsByUserEmailAndBlockedEmail(recipientUser, subscribedSender)).thenReturn(false);

        SpamClassificationResult result = spamClassifierService.classify(msg, recipientUser);

        assertEquals(Verdict.INBOX, result.getVerdict());
        assertEquals("PROMOTIONS", result.getCategory());
        assertFalse(result.isUnsubscribed());
    }

    @Test
    @DisplayName("Scenario 4: Unsubscribed sender → Spam")
    void testScenario4_UnsubscribedSender_RoutesToSpam() throws Exception {
        String unsubSender = "deals@marketingpromos.com";
        Map<String, String> headers = Map.of(
                "List-Unsubscribe", "<https://marketingpromos.com/unsub>"
        );
        Message msg = createMockMessage(unsubSender, "50% Off Everything Today",
                "Huge winter sale going on now.", null, headers, null);

        // In blockedContactRepository (unsubscribed)
        when(blockedContactRepository.existsByUserEmailAndBlockedEmail(recipientUser, unsubSender)).thenReturn(true);

        SpamClassificationResult result = spamClassifierService.classify(msg, recipientUser);

        assertEquals(Verdict.SPAM, result.getVerdict());
        assertEquals("SPAM", result.getCategory());
        assertTrue(result.isUnsubscribed());
        assertEquals(1.0, result.getSpamScore());
    }

    @Test
    @DisplayName("Scenario 5: Blocked sender → Spam")
    void testScenario5_BlockedSender_RoutesToSpam() throws Exception {
        String blockedSender = "annoying.spammer@coldpitch.com";
        Message msg = createMockMessage(blockedSender, "Quick question for you",
                "Wanted to bump my previous email to the top of your inbox.", null, null, null);

        // In blocked sender repository
        when(blockedContactRepository.existsByUserEmailAndBlockedEmail(recipientUser, blockedSender)).thenReturn(true);

        SpamClassificationResult result = spamClassifierService.classify(msg, recipientUser);

        assertEquals(Verdict.SPAM, result.getVerdict());
        assertEquals("SPAM", result.getCategory());
        assertTrue(result.isBlocked());
        assertEquals(1.0, result.getSpamScore());
    }

    @Test
    @DisplayName("Scenario 6: User-reported spam → Spam")
    void testScenario6_UserReportedSpam_RoutesToSpam() throws Exception {
        String reportedSpammer = "junk@maliciousbulk.com";
        Message msg = createMockMessage(reportedSpammer, "Special Promotion",
                "Exclusive benefits for selected users.", null, null, null);

        // User previously marked this sender as REPORTED_SPAM
        SenderReputation rep = SenderReputation.builder()
                .userEmail(recipientUser)
                .senderEmail(reportedSpammer)
                .status("REPORTED_SPAM")
                .build();
        when(senderReputationRepository.findByUserEmailAndSenderEmail(recipientUser, reportedSpammer))
                .thenReturn(Optional.of(rep));

        SpamClassificationResult result = spamClassifierService.classify(msg, recipientUser);

        assertEquals(Verdict.SPAM, result.getVerdict());
        assertEquals("SPAM", result.getCategory());
        assertTrue(result.isUserReported());
        assertEquals(1.0, result.getSpamScore());
    }

    @Test
    @DisplayName("Scenario 7: Confirmed phishing email → Spam")
    void testScenario7_ConfirmedPhishingEmail_RoutesToSpam() throws Exception {
        String phishSender = "support@paypal-security-alert.net";
        String htmlContent = "<p>Your account will be suspended within 24 hours.</p>" +
                "<p>Please verify your password immediately: " +
                "<a href=\"http://192.168.1.100/login\">https://www.paypal.com/signin</a></p>";

        Message msg = createMockMessage(phishSender, "Urgent: Account Verification Required",
                "Your account will be suspended. Verify now.", htmlContent, null, null);

        SpamClassificationResult result = spamClassifierService.classify(msg, recipientUser);

        assertEquals(Verdict.SPAM, result.getVerdict());
        assertEquals("SPAM", result.getCategory());
        assertTrue(result.isPhishing());
        assertEquals(1.0, result.getSpamScore());
    }

    @Test
    @DisplayName("Scenario 8: Spoofed sender → Spam")
    void testScenario8_SpoofedSender_RoutesToSpam() throws Exception {
        String spoofedFrom = "service@apple.com";
        Map<String, String> headers = Map.of(
                "Return-Path", "<attacker@malicious-relay.com>",
                "Authentication-Results", "mx.bnxmail.com; spf=fail (domain of service@apple.com does not designate attacker IP); dkim=fail; dmarc=reject"
        );

        Message msg = createMockMessage(spoofedFrom, "Your Apple ID Statement",
                "Attached is your monthly Apple ID invoice.", null, headers, null);

        SpamClassificationResult result = spamClassifierService.classify(msg, recipientUser);

        assertEquals(Verdict.SPAM, result.getVerdict());
        assertEquals("SPAM", result.getCategory());
        assertTrue(result.isSpoofed());
        assertEquals(1.0, result.getSpamScore());
    }

    @Test
    @DisplayName("Scenario 9: Malicious attachment → Spam")
    void testScenario9_MaliciousAttachment_RoutesToSpam() throws Exception {
        String sender = "accounting@unknownvendor.com";
        List<String> attachments = List.of("Invoice_Nov2026.pdf.exe");

        Message msg = createMockMessage(sender, "Overdue Payment Notice",
                "Please review the attached invoice.", null, null, attachments);

        SpamClassificationResult result = spamClassifierService.classify(msg, recipientUser);

        assertEquals(Verdict.SPAM, result.getVerdict());
        assertEquals("SPAM", result.getCategory());
        assertTrue(result.isHasMaliciousAttachment());
        assertEquals(1.0, result.getSpamScore());
    }

    @Test
    @DisplayName("Scenario 10: Suspicious bulk email → Spam or Review")
    void testScenario10_SuspiciousBulkEmail_RoutesToSpamOrReview() throws Exception {
        String bulkSender = "blast@unknown-massmailer.top";
        Map<String, String> headers = Map.of(
                "Precedence", "bulk",
                "Authentication-Results", "mx.bnxmail.com; spf=fail; dkim=none"
        );
        Message msg = createMockMessage(bulkSender, "Urgent Notice For Recipients",
                "Mass unsolicited announcement with ambiguous links.", null, headers, null);

        SpamClassificationResult result = spamClassifierService.classify(msg, recipientUser);

        assertTrue(result.getVerdict() == Verdict.SPAM || result.getVerdict() == Verdict.REVIEW,
                "Suspicious bulk email must route to SPAM or REVIEW");
        assertTrue(result.getSpamScore() >= 0.35);
    }

    @Test
    @DisplayName("Scenario 11: Uncertain email → Review")
    void testScenario11_UncertainEmail_RoutesToReview() throws Exception {
        String uncertainSender = "organizer@techconferencenew.org";
        Map<String, String> headers = Map.of(
                "Authentication-Results", "mx.bnxmail.com; spf=fail" // soft warning without full spoof
        );
        Message msg = createMockMessage(uncertainSender, "Call for Papers Deadline",
                "Please consider submitting your research paper before the upcoming deadline.", null, headers, null);

        // First time sender
        when(senderReputationRepository.existsByUserEmailAndSenderEmail(anyString(), anyString())).thenReturn(false);

        SpamClassificationResult result = spamClassifierService.classify(msg, recipientUser);

        assertEquals(Verdict.REVIEW, result.getVerdict());
        assertEquals("REVIEW", result.getCategory());
        assertTrue(result.getSpamScore() >= 0.35 && result.getSpamScore() < 0.70);
    }

    @Test
    @DisplayName("Scenario 12: Legitimate promotional email → Inbox")
    void testScenario12_LegitimatePromotionalEmail_RoutesToInbox() throws Exception {
        String promoSender = "offers@store.amazon.com";
        Map<String, String> headers = Map.of(
                "List-Unsubscribe", "<https://amazon.com/opt-out>",
                "Precedence", "bulk",
                "Authentication-Results", "mx.bnxmail.com; spf=pass; dkim=pass; dmarc=pass"
        );
        Message msg = createMockMessage(promoSender, "Today's Lightning Deals",
                "Great savings on electronics and books today.", null, headers, null);

        SpamClassificationResult result = spamClassifierService.classify(msg, recipientUser);

        assertEquals(Verdict.INBOX, result.getVerdict());
        assertEquals("PROMOTIONS", result.getCategory());
        assertFalse(result.isUnsubscribed());
        assertTrue(result.getSpamScore() < 0.35);
    }

    @Test
    @DisplayName("Scenario 13: User marks Spam email as Not Spam → Inbox and Whitelists Sender")
    void testScenario13_UserMarksSpamEmailAsNotSpam_RestoresToInboxAndWhitelistsSender() throws Exception {
        String restoredSender = "legitimate.partner@agency.com";

        // Step 1: User restores email (triggers markSenderAsNotSpam)
        spamClassifierService.markSenderAsNotSpam(recipientUser, restoredSender);

        // Verify reputation saved as TRUSTED
        verify(senderReputationRepository).save(argThat(rep ->
                rep.getUserEmail().equals(recipientUser) &&
                rep.getSenderEmail().equals(restoredSender) &&
                "TRUSTED".equals(rep.getStatus())
        ));
        // Verify any blocked record is removed
        verify(blockedContactRepository).deleteByUserEmailAndBlockedEmail(recipientUser, restoredSender);

        // Step 2: Next incoming email from this sender arrives
        Message futureMsg = createMockMessage(restoredSender, "Follow-up on our meeting",
                "Here are the notes from our discussion yesterday.", null, null, null);

        SenderReputation rep = SenderReputation.builder()
                .userEmail(recipientUser)
                .senderEmail(restoredSender)
                .status("TRUSTED")
                .build();
        when(senderReputationRepository.findByUserEmailAndSenderEmail(recipientUser, restoredSender))
                .thenReturn(Optional.of(rep));

        SpamClassificationResult result = spamClassifierService.classify(futureMsg, recipientUser);

        assertEquals(Verdict.INBOX, result.getVerdict());
        assertTrue(result.isTrusted());
        assertEquals(0.0, result.getSpamScore());
    }
}
