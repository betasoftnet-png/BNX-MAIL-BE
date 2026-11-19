package com.btctech.mailapp.service;

import com.btctech.mailapp.dto.AttachmentInfo;
import com.btctech.mailapp.dto.SendMailRequest;
import com.btctech.mailapp.entity.PendingEmail;
import com.btctech.mailapp.exception.MailException;
import com.btctech.mailapp.repository.PendingEmailRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PendingEmailServiceTest {

    private PendingEmailRepository pendingEmailRepository;
    private MailSendService mailSendService;
    private SessionService sessionService;
    private MailboxService mailboxService;
    private ObjectMapper objectMapper;

    private PendingEmailService pendingEmailService;

    @BeforeEach
    void setUp() {
        pendingEmailRepository = mock(PendingEmailRepository.class);
        mailSendService = mock(MailSendService.class);
        sessionService = mock(SessionService.class);
        mailboxService = mock(MailboxService.class);
        objectMapper = new ObjectMapper();

        pendingEmailService = new PendingEmailService(
                pendingEmailRepository,
                mailSendService,
                sessionService,
                mailboxService,
                objectMapper
        );
    }

    @Test
    @DisplayName("Test 1 & 2: Undo before and immediately before deadline succeeds")
    void testUndoBeforeDeadline() {
        String sendId = "send-undo-before";
        String userEmail = "sender@bnxmail.com";

        PendingEmail cancelled = PendingEmail.builder()
                .sendId(sendId)
                .userEmail(userEmail)
                .status("CANCELLED")
                .toRecipient("receiver@bnxmail.com")
                .subject("Test Subject")
                .body("Hello World")
                .undoDeadline(LocalDateTime.now().plusSeconds(4))
                .build();

        when(pendingEmailRepository.cancelIfPendingAndBeforeDeadline(eq(sendId), any()))
                .thenReturn(1);
        when(pendingEmailRepository.findBySendIdAndUserEmail(sendId, userEmail))
                .thenReturn(Optional.of(cancelled));

        PendingEmail result = pendingEmailService.cancelPendingEmail(sendId, userEmail);

        assertNotNull(result);
        assertEquals("CANCELLED", result.getStatus());
        assertEquals("receiver@bnxmail.com", result.getToRecipient());
        verify(pendingEmailRepository, times(1)).cancelIfPendingAndBeforeDeadline(eq(sendId), any());
    }

    @Test
    @DisplayName("Test 3: Undo after deadline fails and does not falsely claim success")
    void testUndoAfterDeadlineFails() {
        String sendId = "send-undo-expired";
        String userEmail = "sender@bnxmail.com";

        // Deadline has passed
        PendingEmail expired = PendingEmail.builder()
                .sendId(sendId)
                .userEmail(userEmail)
                .status("PENDING_UNDO")
                .toRecipient("receiver@bnxmail.com")
                .undoDeadline(LocalDateTime.now().minusSeconds(1))
                .build();

        when(pendingEmailRepository.cancelIfPendingAndBeforeDeadline(eq(sendId), any()))
                .thenReturn(0);
        when(pendingEmailRepository.findBySendIdAndUserEmail(sendId, userEmail))
                .thenReturn(Optional.of(expired));

        MailException ex = assertThrows(MailException.class, () ->
                pendingEmailService.cancelPendingEmail(sendId, userEmail)
        );

        assertTrue(ex.getMessage().contains("expired"));
    }

    @Test
    @DisplayName("Test 3b: Undo after delivery has started (SENDING or SENT) fails")
    void testUndoAfterDeliveryStartedFails() {
        String sendId = "send-in-flight";
        String userEmail = "sender@bnxmail.com";

        PendingEmail inFlight = PendingEmail.builder()
                .sendId(sendId)
                .userEmail(userEmail)
                .status("SENDING")
                .undoDeadline(LocalDateTime.now().minusSeconds(1))
                .build();

        when(pendingEmailRepository.cancelIfPendingAndBeforeDeadline(eq(sendId), any()))
                .thenReturn(0);
        when(pendingEmailRepository.findBySendIdAndUserEmail(sendId, userEmail))
                .thenReturn(Optional.of(inFlight));

        MailException ex = assertThrows(MailException.class, () ->
                pendingEmailService.cancelPendingEmail(sendId, userEmail)
        );

        assertTrue(ex.getMessage().contains("already being sent") || ex.getMessage().contains("already been delivered"));
    }

    @Test
    @DisplayName("Test 4: View Message retrieves pending email preview without changing state")
    void testViewMessageWithoutChangingState() {
        String sendId = "send-view-123";
        String userEmail = "sender@bnxmail.com";

        PendingEmail pending = PendingEmail.builder()
                .sendId(sendId)
                .userEmail(userEmail)
                .toRecipient("receiver@bnxmail.com")
                .subject("Preview Subject")
                .body("<p>Preview Body</p>")
                .status("PENDING_UNDO")
                .undoDeadline(LocalDateTime.now().plusSeconds(3))
                .build();

        when(pendingEmailRepository.findBySendIdAndUserEmail(sendId, userEmail))
                .thenReturn(Optional.of(pending));

        Optional<PendingEmail> resultOpt = pendingEmailService.getPendingEmail(sendId, userEmail);

        assertTrue(resultOpt.isPresent());
        assertEquals("PENDING_UNDO", resultOpt.get().getStatus());
        assertEquals("Preview Subject", resultOpt.get().getSubject());
        // Verify no status updates occurred
        verify(pendingEmailRepository, never()).updateStatusIfCurrentStatus(any(), any(), any(), any());
        verify(pendingEmailRepository, never()).cancelIfPendingAndBeforeDeadline(any(), any());
    }

    @Test
    @DisplayName("Test 5: Browser refresh rehydrates active pending emails from backend")
    void testActivePendingEmailsOnRefresh() {
        String userEmail = "sender@bnxmail.com";

        PendingEmail p1 = PendingEmail.builder()
                .sendId("send-1")
                .userEmail(userEmail)
                .status("PENDING_UNDO")
                .undoDeadline(LocalDateTime.now().plusSeconds(3))
                .build();

        PendingEmail p2 = PendingEmail.builder()
                .sendId("send-2")
                .userEmail(userEmail)
                .status("PENDING_UNDO")
                .undoDeadline(LocalDateTime.now().plusSeconds(4))
                .build();

        when(pendingEmailRepository.findByUserEmailAndStatus(userEmail, "PENDING_UNDO"))
                .thenReturn(List.of(p1, p2));

        List<PendingEmail> active = pendingEmailService.getActivePendingEmails(userEmail);

        assertEquals(2, active.size());
        assertEquals("send-1", active.get(0).getSendId());
        assertEquals("send-2", active.get(1).getSendId());
    }

    @Test
    @DisplayName("Test 6: Multiple pending emails handled independently")
    void testMultiplePendingEmailsIndependent() {
        SendMailRequest r1 = new SendMailRequest();
        r1.setTo("user1@example.com");
        r1.setSubject("Subject 1");
        r1.setBody("Body 1");
        r1.setSendId("send-multi-1");

        SendMailRequest r2 = new SendMailRequest();
        r2.setTo("user2@example.com");
        r2.setSubject("Subject 2");
        r2.setBody("Body 2");
        r2.setSendId("send-multi-2");

        when(pendingEmailRepository.findBySendId(anyString())).thenReturn(Optional.empty());
        when(pendingEmailRepository.save(any(PendingEmail.class))).thenAnswer(i -> i.getArgument(0));

        PendingEmail p1 = pendingEmailService.queuePendingEmail("sender@bnxmail.com", 1L, 10L, "pass", r1, 5);
        PendingEmail p2 = pendingEmailService.queuePendingEmail("sender@bnxmail.com", 1L, 10L, "pass", r2, 5);

        assertNotNull(p1);
        assertNotNull(p2);
        assertNotEquals(p1.getSendId(), p2.getSendId());
        assertEquals("user1@example.com", p1.getToRecipient());
        assertEquals("user2@example.com", p2.getToRecipient());
    }

    @Test
    @DisplayName("Test 7: Repeated Send requests return existing record without duplicate queuing")
    void testRepeatedSendIdempotency() {
        SendMailRequest r = new SendMailRequest();
        r.setTo("user@example.com");
        r.setSubject("Subject");
        r.setBody("Body");
        r.setSendId("send-dup-check");

        PendingEmail existing = PendingEmail.builder()
                .sendId("send-dup-check")
                .status("PENDING_UNDO")
                .toRecipient("user@example.com")
                .build();

        when(pendingEmailRepository.findBySendId("send-dup-check")).thenReturn(Optional.of(existing));

        PendingEmail res = pendingEmailService.queuePendingEmail("sender@bnxmail.com", 1L, 10L, "pass", r, 5);

        assertSame(existing, res);
        verify(pendingEmailRepository, never()).save(any(PendingEmail.class));
    }

    @Test
    @DisplayName("Test 8: Application restart recovers expired pending emails safely")
    void testApplicationRestartRecovery() throws Exception {
        PendingEmail expired = PendingEmail.builder()
                .sendId("send-restart-due")
                .userEmail("sender@bnxmail.com")
                .toRecipient("recipient@example.com")
                .subject("Restart Subject")
                .body("Content")
                .status("PENDING_UNDO")
                .undoDeadline(LocalDateTime.now().minusSeconds(10))
                .build();

        when(pendingEmailRepository.findByStatusAndUndoDeadlineBefore(eq("PENDING_UNDO"), any()))
                .thenReturn(List.of(expired));
        when(pendingEmailRepository.claimForDeliveryIfDue(eq("send-restart-due"), any())).thenReturn(1);
        when(pendingEmailRepository.findBySendId("send-restart-due")).thenReturn(Optional.of(expired));
        when(mailSendService.sendMail(anyString(), any(), any())).thenReturn("uid-recovered-101");

        // Simulate startup recovery trigger
        pendingEmailService.onStartup();

        verify(mailSendService, times(1)).sendMail(eq("sender@bnxmail.com"), any(), any());
        verify(pendingEmailRepository, times(1)).markSent(eq("send-restart-due"), eq("SENT"), eq("uid-recovered-101"), any());
    }

    @Test
    @DisplayName("Test 9: SMTP/delivery failure records FAILED and does not mark SENT")
    void testDeliveryFailureRecordedAsFailed() throws Exception {
        String sendId = "send-smtp-fail";
        PendingEmail pending = PendingEmail.builder()
                .sendId(sendId)
                .userEmail("sender@bnxmail.com")
                .toRecipient("receiver@bnxmail.com")
                .subject("Subject")
                .body("Body")
                .status("PENDING_UNDO")
                .undoDeadline(LocalDateTime.now().minusSeconds(1))
                .build();

        when(pendingEmailRepository.claimForDeliveryIfDue(eq(sendId), any())).thenReturn(1);
        when(pendingEmailRepository.findBySendId(sendId)).thenReturn(Optional.of(pending));
        when(mailSendService.sendMail(anyString(), any(), any())).thenThrow(new MailException("SMTP connection refused"));

        pendingEmailService.deliverPendingEmail(sendId);

        verify(pendingEmailRepository, times(1)).markFailed(eq(sendId), eq("FAILED"), contains("SMTP connection refused"), any());
        verify(pendingEmailRepository, never()).markSent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Test 10: Successful cancellation preserves original compose payload for editing and resending")
    void testCancelledEmailPreservesOriginalComposePayload() {
        String sendId = "send-restore-compose";
        String userEmail = "sender@bnxmail.com";

        PendingEmail cancelled = PendingEmail.builder()
                .sendId(sendId)
                .userEmail(userEmail)
                .status("CANCELLED")
                .toRecipient("client@bnxmail.com")
                .cc("manager@bnxmail.com")
                .bcc("archive@bnxmail.com")
                .subject("Important Contract")
                .body("<p>Please review and sign attached.</p>")
                .fromName("Alice")
                .isHtml(true)
                .attachmentsJson("[{\"fileName\":\"contract.pdf\",\"filePath\":\"/tmp/contract.pdf\",\"size\":1024}]")
                .draftId(42L)
                .draftUid("draft-42")
                .isStarred(true)
                .undoDeadline(LocalDateTime.now().plusSeconds(3))
                .build();

        when(pendingEmailRepository.cancelIfPendingAndBeforeDeadline(eq(sendId), any())).thenReturn(1);
        when(pendingEmailRepository.findBySendIdAndUserEmail(sendId, userEmail)).thenReturn(Optional.of(cancelled));

        PendingEmail result = pendingEmailService.cancelPendingEmail(sendId, userEmail);

        assertEquals("client@bnxmail.com", result.getToRecipient());
        assertEquals("manager@bnxmail.com", result.getCc());
        assertEquals("archive@bnxmail.com", result.getBcc());
        assertEquals("Important Contract", result.getSubject());
        assertEquals("<p>Please review and sign attached.</p>", result.getBody());
        assertTrue(result.getIsStarred());

        List<AttachmentInfo> attachments = pendingEmailService.parseAttachments(result.getAttachmentsJson());
        assertEquals(1, attachments.size());
        assertEquals("contract.pdf", attachments.get(0).getFileName());
    }
}
