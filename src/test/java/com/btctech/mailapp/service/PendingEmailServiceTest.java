package com.btctech.mailapp.service;

import com.btctech.mailapp.dto.SendMailRequest;
import com.btctech.mailapp.entity.PendingEmail;
import com.btctech.mailapp.exception.MailException;
import com.btctech.mailapp.repository.PendingEmailRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
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
    @DisplayName("queuePendingEmail: creates email in PENDING_UNDO status with 5 second delay")
    void testQueuePendingEmail() {
        SendMailRequest request = new SendMailRequest();
        request.setTo("receiver@bnxmail.com");
        request.setSubject("Test Subject");
        request.setBody("Hello World");
        request.setSendId("send-test-123");

        when(pendingEmailRepository.findBySendId("send-test-123")).thenReturn(Optional.empty());
        when(pendingEmailRepository.save(any(PendingEmail.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PendingEmail pending = pendingEmailService.queuePendingEmail(
                "sender@bnxmail.com", 1L, 10L, "secretPass", request, 5
        );

        assertNotNull(pending);
        assertEquals("send-test-123", pending.getSendId());
        assertEquals("sender@bnxmail.com", pending.getUserEmail());
        assertEquals("receiver@bnxmail.com", pending.getToRecipient());
        assertEquals("PENDING_UNDO", pending.getStatus());
        assertTrue(pending.getScheduledAt().isAfter(LocalDateTime.now().plusSeconds(4)));
        verify(pendingEmailRepository, times(1)).save(any(PendingEmail.class));
    }

    @Test
    @DisplayName("queuePendingEmail: idempotency returns existing record on duplicate sendId")
    void testQueuePendingEmailDuplicate() {
        SendMailRequest request = new SendMailRequest();
        request.setTo("receiver@bnxmail.com");
        request.setSubject("Test Subject");
        request.setBody("Hello World");
        request.setSendId("send-dup-123");

        PendingEmail existing = PendingEmail.builder()
                .sendId("send-dup-123")
                .status("PENDING_UNDO")
                .toRecipient("receiver@bnxmail.com")
                .build();

        when(pendingEmailRepository.findBySendId("send-dup-123")).thenReturn(Optional.of(existing));

        PendingEmail result = pendingEmailService.queuePendingEmail(
                "sender@bnxmail.com", 1L, 10L, "secretPass", request, 5
        );

        assertSame(existing, result);
        verify(pendingEmailRepository, never()).save(any(PendingEmail.class));
    }

    @Test
    @DisplayName("cancelPendingEmail: successfully cancels when in PENDING_UNDO state")
    void testCancelPendingEmailSuccess() {
        String sendId = "send-cancel-123";
        String userEmail = "sender@bnxmail.com";

        PendingEmail cancelled = PendingEmail.builder()
                .sendId(sendId)
                .userEmail(userEmail)
                .status("CANCELLED")
                .toRecipient("receiver@bnxmail.com")
                .subject("Test Subject")
                .body("Hello World")
                .build();

        when(pendingEmailRepository.updateStatusIfCurrentStatus(eq(sendId), eq("PENDING_UNDO"), eq("CANCELLED"), any()))
                .thenReturn(1);
        when(pendingEmailRepository.findBySendIdAndUserEmail(sendId, userEmail))
                .thenReturn(Optional.of(cancelled));

        PendingEmail result = pendingEmailService.cancelPendingEmail(sendId, userEmail);

        assertNotNull(result);
        assertEquals("CANCELLED", result.getStatus());
        assertEquals("receiver@bnxmail.com", result.getToRecipient());
    }

    @Test
    @DisplayName("cancelPendingEmail: rejects undo if message is already SENDING or SENT")
    void testCancelPendingEmailAlreadySending() {
        String sendId = "send-already-sending";
        String userEmail = "sender@bnxmail.com";

        PendingEmail alreadySending = PendingEmail.builder()
                .sendId(sendId)
                .userEmail(userEmail)
                .status("SENDING")
                .build();

        when(pendingEmailRepository.updateStatusIfCurrentStatus(eq(sendId), eq("PENDING_UNDO"), eq("CANCELLED"), any()))
                .thenReturn(0);
        when(pendingEmailRepository.findBySendIdAndUserEmail(sendId, userEmail))
                .thenReturn(Optional.of(alreadySending));

        MailException ex = assertThrows(MailException.class, () ->
                pendingEmailService.cancelPendingEmail(sendId, userEmail)
        );

        assertTrue(ex.getMessage().contains("already being sent") || ex.getMessage().contains("already been delivered"));
    }

    @Test
    @DisplayName("deliverPendingEmail: atomic transition PENDING_UNDO -> SENDING -> SENT")
    void testDeliverPendingEmailSuccess() throws Exception {
        String sendId = "send-deliver-123";

        PendingEmail pending = PendingEmail.builder()
                .sendId(sendId)
                .userEmail("sender@bnxmail.com")
                .toRecipient("receiver@bnxmail.com")
                .subject("Subject")
                .body("Body")
                .encryptedPassword("encrypted")
                .status("PENDING_UNDO")
                .build();

        when(pendingEmailRepository.updateStatusIfCurrentStatus(eq(sendId), eq("PENDING_UNDO"), eq("SENDING"), any()))
                .thenReturn(1);
        when(pendingEmailRepository.findBySendId(sendId)).thenReturn(Optional.of(pending));
        when(sessionService.decrypt("encrypted")).thenReturn("rawPassword");
        when(mailSendService.sendMail(eq("sender@bnxmail.com"), eq("rawPassword"), any())).thenReturn("uid-999");

        pendingEmailService.deliverPendingEmail(sendId);

        verify(mailSendService, times(1)).sendMail(eq("sender@bnxmail.com"), eq("rawPassword"), any());
        verify(pendingEmailRepository, times(1)).markSent(eq(sendId), eq("SENT"), eq("uid-999"), any());
    }

    @Test
    @DisplayName("deliverPendingEmail: skips delivery if already cancelled")
    void testDeliverPendingEmailAlreadyCancelled() {
        String sendId = "send-already-cancelled";

        when(pendingEmailRepository.updateStatusIfCurrentStatus(eq(sendId), eq("PENDING_UNDO"), eq("SENDING"), any()))
                .thenReturn(0);

        pendingEmailService.deliverPendingEmail(sendId);

        verify(mailSendService, never()).sendMail(any(), any(), any());
    }
}
