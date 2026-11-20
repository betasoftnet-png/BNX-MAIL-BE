package com.btctech.mailapp.service;

import com.btctech.mailapp.dto.EmailDTO;
import com.btctech.mailapp.entity.AccountType;
import com.btctech.mailapp.entity.Organization;
import com.btctech.mailapp.entity.ReadReceipt;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.exception.MailException;
import com.btctech.mailapp.repository.ReadReceiptRepository;
import com.btctech.mailapp.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReadReceiptServiceTest {

    private ReadReceiptRepository readReceiptRepository;
    private UserRepository userRepository;
    private ReadReceiptService readReceiptService;

    @BeforeEach
    void setUp() {
        readReceiptRepository = mock(ReadReceiptRepository.class);
        userRepository = mock(UserRepository.class);
        readReceiptService = new ReadReceiptService(readReceiptRepository, userRepository);
    }

    @Test
    @DisplayName("Scenario 1: Business Account can request read receipt")
    void testBusinessAccountCanRequestReadReceipt() {
        User businessUser = new User();
        businessUser.setId(1L);
        businessUser.setEmail("ceo@businesscorp.com");
        businessUser.setAccountType(AccountType.BUSINESS);
        when(userRepository.findByEmail("ceo@businesscorp.com")).thenReturn(Optional.of(businessUser));

        when(readReceiptRepository.save(any(ReadReceipt.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReadReceipt receipt = readReceiptService.createReceiptRequest(
                "ceo@businesscorp.com", 1L, "client@partner.com", "Project Proposal", "send-123"
        );

        assertNotNull(receipt);
        assertTrue(receipt.getReceiptId().startsWith("rr-"));
        assertEquals("ceo@businesscorp.com", receipt.getSenderEmail());
        assertEquals("client@partner.com", receipt.getRecipientEmail());
        assertEquals("PENDING", receipt.getStatus());
        assertEquals("send-123", receipt.getSendId());
    }

    @Test
    @DisplayName("Scenario 2: Personal Account cannot request read receipt (throws MailException)")
    void testPersonalAccountCannotRequestReadReceipt() {
        User personalUser = new User();
        personalUser.setId(2L);
        personalUser.setEmail("john.doe@bnxmail.com");
        personalUser.setAccountType(AccountType.PUBLIC);
        when(userRepository.findByEmail("john.doe@bnxmail.com")).thenReturn(Optional.of(personalUser));

        MailException exception = assertThrows(MailException.class, () ->
                readReceiptService.createReceiptRequest(
                        "john.doe@bnxmail.com", 2L, "someone@external.com", "Hello", "send-456"
                )
        );

        assertTrue(exception.getMessage().contains("only available to Business Accounts"));
        verify(readReceiptRepository, never()).save(any());
    }

    @Test
    @DisplayName("Scenario 3: Header tracking - associate sent details links messageId and sentUid")
    void testAssociateSentDetailsLinksHeaders() {
        ReadReceipt receipt = ReadReceipt.builder()
                .receiptId("rr-abc-123")
                .senderEmail("business@corp.com")
                .status("PENDING")
                .build();
        when(readReceiptRepository.findByReceiptId("rr-abc-123")).thenReturn(Optional.of(receipt));

        readReceiptService.associateSentDetails("rr-abc-123", "<msg-999@mail.bnxmail.com>", "UID-888");

        assertEquals("<msg-999@mail.bnxmail.com>", receipt.getMessageId());
        assertEquals("UID-888", receipt.getSentUid());
        verify(readReceiptRepository).save(receipt);
    }

    @Test
    @DisplayName("Scenario 4: Delivery does NOT trigger a read receipt (status remains PENDING)")
    void testDeliveryDoesNotTriggerReadReceipt() {
        ReadReceipt receipt = ReadReceipt.builder()
                .receiptId("rr-xyz-789")
                .senderEmail("biz@corp.com")
                .status("PENDING")
                .build();
        when(readReceiptRepository.findByReceiptId("rr-xyz-789")).thenReturn(Optional.of(receipt));

        // Delivery only associates sent details
        readReceiptService.associateSentDetails("rr-xyz-789", "<msg-id>", "101");

        assertEquals("PENDING", receipt.getStatus(), "Delivery must NOT transition status to READ");
        assertNull(receipt.getRespondedAt());
    }

    @Test
    @DisplayName("Scenario 5: Recipient accepts -> Read status recorded with timestamp")
    void testRecipientAcceptsReceipt() {
        ReadReceipt receipt = ReadReceipt.builder()
                .receiptId("rr-target-1")
                .senderEmail("boss@company.com")
                .recipientEmail("employee@company.com")
                .status("PENDING")
                .build();
        when(readReceiptRepository.findByReceiptId("rr-target-1")).thenReturn(Optional.of(receipt));
        when(readReceiptRepository.save(any(ReadReceipt.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReadReceipt updated = readReceiptService.respondToReceipt(
                "rr-target-1", "employee@company.com", "ACCEPT", "Mozilla/5.0 BNXmail Client", null
        );

        assertEquals("READ", updated.getStatus());
        assertNotNull(updated.getRespondedAt());
        assertEquals("Mozilla/5.0 BNXmail Client", updated.getRecipientUserAgent());
    }

    @Test
    @DisplayName("Scenario 6: Recipient declines -> Declined status recorded with reason")
    void testRecipientDeclinesReceipt() {
        ReadReceipt receipt = ReadReceipt.builder()
                .receiptId("rr-target-2")
                .senderEmail("boss@company.com")
                .recipientEmail("client@client.com")
                .status("PENDING")
                .build();
        when(readReceiptRepository.findByReceiptId("rr-target-2")).thenReturn(Optional.of(receipt));
        when(readReceiptRepository.save(any(ReadReceipt.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReadReceipt updated = readReceiptService.respondToReceipt(
                "rr-target-2", "client@client.com", "DECLINE", null, "Recipient declined read receipt request"
        );

        assertEquals("DECLINED", updated.getStatus());
        assertNotNull(updated.getRespondedAt());
        assertEquals("Recipient declined read receipt request", updated.getDeclinedReason());
    }

    @Test
    @DisplayName("Scenario 7: Client unsupported -> Unsupported status recorded")
    void testClientUnsupportedStatusRecorded() {
        ReadReceipt receipt = ReadReceipt.builder()
                .receiptId("rr-target-3")
                .senderEmail("biz@corp.com")
                .status("PENDING")
                .build();
        when(readReceiptRepository.findByReceiptId("rr-target-3")).thenReturn(Optional.of(receipt));
        when(readReceiptRepository.save(any(ReadReceipt.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReadReceipt updated = readReceiptService.updateReceiptStatus(
                "rr-target-3", "UNSUPPORTED", "Client does not support disposition notifications"
        );

        assertEquals("UNSUPPORTED", updated.getStatus());
        assertNotNull(updated.getRespondedAt());
        assertEquals("Client does not support disposition notifications", updated.getDeclinedReason());
    }

    @Test
    @DisplayName("Scenario 8: Duplicate receipt submission prevented")
    void testDuplicateReceiptSubmissionPrevented() {
        ReadReceipt receipt = ReadReceipt.builder()
                .receiptId("rr-target-4")
                .senderEmail("boss@corp.com")
                .status("READ")
                .respondedAt(LocalDateTime.now().minusMinutes(5))
                .build();
        when(readReceiptRepository.findByReceiptId("rr-target-4")).thenReturn(Optional.of(receipt));

        MailException exception = assertThrows(MailException.class, () ->
                readReceiptService.respondToReceipt("rr-target-4", "other@corp.com", "ACCEPT", "Chrome", null)
        );

        assertTrue(exception.getMessage().contains("already been processed"));
    }

    @Test
    @DisplayName("Scenario 9: Sent email details show accurate read status (DTO enrichment)")
    void testSentEmailDtoEnrichmentWithReadStatus() {
        User businessUser = new User();
        businessUser.setId(10L);
        businessUser.setEmail("vendor@supply.com");
        businessUser.setAccountType(AccountType.BUSINESS);
        when(userRepository.findByEmail("vendor@supply.com")).thenReturn(Optional.of(businessUser));

        ReadReceipt receipt = ReadReceipt.builder()
                .receiptId("rr-enriched-1")
                .senderEmail("vendor@supply.com")
                .recipientEmail("buyer@store.com")
                .sentUid("555")
                .status("READ")
                .respondedAt(LocalDateTime.now())
                .build();
        when(readReceiptRepository.findBySentUid("555")).thenReturn(Optional.of(receipt));

        EmailDTO dto = new EmailDTO();
        dto.setUid("555");
        dto.setSubject("Invoice #101");

        readReceiptService.enrichSentEmailDtoWithReceipt(dto, "vendor@supply.com");

        assertTrue(Boolean.TRUE.equals(dto.getReadReceiptRequested()));
        assertEquals("rr-enriched-1", dto.getReadReceiptId());
        assertEquals("READ", dto.getReadReceiptStatus());
        assertEquals("vendor@supply.com", dto.getReadReceiptSender());
        assertEquals("buyer@store.com", dto.getReadReceiptRecipient());
        assertNotNull(dto.getReadReceiptRespondedAt());
    }

    @Test
    @DisplayName("Scenario 10: Personal Accounts cannot call read receipt APIs")
    void testPersonalAccountCannotAccessReadReceiptApi() {
        User personalUser = new User();
        personalUser.setId(99L);
        personalUser.setEmail("regular.user@bnxmail.com");
        personalUser.setAccountType(AccountType.PUBLIC);
        when(userRepository.findByEmail("regular.user@bnxmail.com")).thenReturn(Optional.of(personalUser));

        MailException exception = assertThrows(MailException.class, () ->
                readReceiptService.getReceiptForSender("rr-test", "regular.user@bnxmail.com")
        );

        assertTrue(exception.getMessage().contains("only available to Business Accounts"));
    }
}
