package com.btctech.mailapp.service;

import com.btctech.mailapp.dto.EmailDTO;
import com.btctech.mailapp.entity.AccountType;
import com.btctech.mailapp.entity.ReadReceipt;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.exception.MailException;
import com.btctech.mailapp.repository.ReadReceiptRepository;
import com.btctech.mailapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReadReceiptService {

    private final ReadReceiptRepository readReceiptRepository;
    private final UserRepository userRepository;

    /**
     * Identifies whether the given user email belongs to a Business Account.
     * Enforces AccountType.BUSINESS, organization link, or business role.
     * Reuses existing account-type architecture.
     */
    public boolean isBusinessAccount(String email) {
        if (email == null || email.trim().isEmpty()) {
            return false;
        }
        String cleanEmail = email.trim();
        Optional<User> userOpt = userRepository.findByEmail(cleanEmail);
        if (userOpt.isEmpty()) {
            userOpt = userRepository.findByMailAccountEmailIgnoreCase(cleanEmail);
        }
        if (userOpt.isEmpty()) {
            userOpt = userRepository.findByUsernameIgnoreCase(cleanEmail);
        }

        if (userOpt.isEmpty()) {
            return false;
        }

        User user = userOpt.get();

        // 1. Explicit AccountType enum check
        if (AccountType.BUSINESS.equals(user.getAccountType())) {
            return true;
        }

        // 2. Organization or business role linkage
        if (user.getOrganization() != null) {
            return true;
        }

        if (user.getRole() != null) {
            String roleUpper = user.getRole().trim().toUpperCase();
            if (roleUpper.contains("BUSINESS") || roleUpper.contains("ORGANIZATION_ADMIN")) {
                return true;
            }
        }

        return false;
    }

    /**
     * Creates a new ReadReceipt tracking request for an outgoing email.
     * Strictly validates sender's Business Account eligibility.
     */
    @Transactional
    public ReadReceipt createReceiptRequest(String senderEmail, Long senderUserId, String recipientEmail, String subject, String sendId) {
        if (!isBusinessAccount(senderEmail)) {
            throw new MailException("Read receipt feature is only available to Business Accounts");
        }

        String trackingId = "rr-" + UUID.randomUUID().toString();

        ReadReceipt receipt = ReadReceipt.builder()
                .receiptId(trackingId)
                .senderEmail(senderEmail)
                .senderUserId(senderUserId)
                .recipientEmail(recipientEmail != null ? recipientEmail : "")
                .subject(subject)
                .sendId(sendId)
                .status("PENDING")
                .requestedAt(LocalDateTime.now())
                .build();

        log.info("Created Read Receipt request {} for sender {} to {}", trackingId, senderEmail, recipientEmail);
        return readReceiptRepository.save(receipt);
    }

    /**
     * Responds to a Read Receipt request (ACCEPT or DECLINE).
     * Enforces atomic transition: only PENDING receipts can be processed.
     * Prevents duplicate submissions.
     */
    @Transactional
    public ReadReceipt respondToReceipt(String receiptId, String recipientEmail, String action, String userAgent, String reason) {
        if (receiptId == null || receiptId.trim().isEmpty()) {
            throw new MailException("Invalid receipt ID");
        }

        ReadReceipt receipt = readReceiptRepository.findByReceiptId(receiptId.trim())
                .orElseThrow(() -> new MailException("Read receipt not found for ID: " + receiptId));

        // Atomic check: prevent duplicate submissions
        if (!"PENDING".equalsIgnoreCase(receipt.getStatus())) {
            throw new MailException("Read receipt has already been processed with status: " + receipt.getStatus());
        }

        String actUpper = action != null ? action.trim().toUpperCase() : "ACCEPT";

        if ("DECLINE".equals(actUpper) || "DECLINED".equals(actUpper)) {
            receipt.setStatus("DECLINED");
            receipt.setDeclinedReason(reason != null ? reason : "Recipient declined to send read receipt");
            receipt.setRespondedAt(LocalDateTime.now());
            log.info("Read receipt {} DECLINED by recipient {}", receiptId, recipientEmail);
        } else {
            // ACCEPT -> mark as READ
            receipt.setStatus("READ");
            receipt.setRespondedAt(LocalDateTime.now());
            receipt.setRecipientUserAgent(userAgent);
            log.info("Read receipt {} ACCEPTED (status READ) by recipient {}", receiptId, recipientEmail);
        }

        return readReceiptRepository.save(receipt);
    }

    /**
     * Updates receipt status for unsupported client or processing failure.
     */
    @Transactional
    public ReadReceipt updateReceiptStatus(String receiptId, String newStatus, String reason) {
        ReadReceipt receipt = readReceiptRepository.findByReceiptId(receiptId)
                .orElseThrow(() -> new MailException("Read receipt not found for ID: " + receiptId));

        if (!"PENDING".equalsIgnoreCase(receipt.getStatus())) {
            throw new MailException("Read receipt has already been processed with status: " + receipt.getStatus());
        }

        String statusUpper = newStatus != null ? newStatus.trim().toUpperCase() : "FAILED";
        if (!"UNSUPPORTED".equals(statusUpper) && !"FAILED".equals(statusUpper) && !"READ".equals(statusUpper) && !"DECLINED".equals(statusUpper)) {
            throw new MailException("Invalid status update: " + newStatus);
        }

        receipt.setStatus(statusUpper);
        receipt.setRespondedAt(LocalDateTime.now());
        if (reason != null) {
            receipt.setDeclinedReason(reason);
        }

        return readReceiptRepository.save(receipt);
    }

    /**
     * Retrieves receipt status for a business account sender.
     * Enforces ownership and business account eligibility.
     */
    public ReadReceipt getReceiptForSender(String receiptId, String senderEmail) {
        if (!isBusinessAccount(senderEmail)) {
            throw new MailException("Read receipt feature is only available to Business Accounts");
        }

        ReadReceipt receipt = readReceiptRepository.findByReceiptId(receiptId)
                .orElseThrow(() -> new MailException("Read receipt not found"));

        if (!receipt.getSenderEmail().equalsIgnoreCase(senderEmail)) {
            throw new MailException("You are not authorized to view this read receipt");
        }

        return receipt;
    }

    /**
     * Links messageId and sentUid after email is delivered via SMTP / IMAP.
     */
    @Transactional
    public void associateSentDetails(String receiptId, String messageId, String sentUid) {
        if (receiptId == null) return;
        readReceiptRepository.findByReceiptId(receiptId).ifPresent(receipt -> {
            if (messageId != null && !messageId.trim().isEmpty()) {
                receipt.setMessageId(messageId);
            }
            if (sentUid != null && !sentUid.trim().isEmpty()) {
                receipt.setSentUid(sentUid);
            }
            readReceiptRepository.save(receipt);
        });
    }

    /**
     * Enriches recipient's EmailDTO with receipt request metadata if applicable.
     */
    public void enrichEmailDtoWithReceipt(EmailDTO dto, String userEmail) {
        if (dto == null) return;
        String rId = dto.getReadReceiptId();
        if (rId != null && !rId.isEmpty()) {
            readReceiptRepository.findByReceiptId(rId).ifPresent(receipt -> {
                dto.setReadReceiptRequested(true);
                dto.setReadReceiptStatus(receipt.getStatus());
                dto.setReadReceiptSender(receipt.getSenderEmail());
                dto.setReadReceiptRecipient(receipt.getRecipientEmail());
                if (receipt.getRespondedAt() != null) {
                    dto.setReadReceiptRespondedAt(Date.from(receipt.getRespondedAt().atZone(ZoneId.systemDefault()).toInstant()));
                }
            });
        }
    }

    /**
     * Enriches sender's sent EmailDTO with receipt status if sender is a Business Account.
     */
    public void enrichSentEmailDtoWithReceipt(EmailDTO dto, String userEmail) {
        if (dto == null || !isBusinessAccount(userEmail)) return;

        Optional<ReadReceipt> receiptOpt = Optional.empty();

        if (dto.getUid() != null) {
            receiptOpt = readReceiptRepository.findBySentUid(dto.getUid());
        }
        if (receiptOpt.isEmpty() && dto.getMessageId() != null) {
            receiptOpt = readReceiptRepository.findByMessageId(dto.getMessageId());
        }
        if (receiptOpt.isEmpty() && dto.getSubject() != null) {
            receiptOpt = readReceiptRepository.findFirstBySenderEmailAndSubjectOrderByRequestedAtDesc(userEmail, dto.getSubject());
        }

        receiptOpt.ifPresent(receipt -> {
            dto.setReadReceiptRequested(true);
            dto.setReadReceiptId(receipt.getReceiptId());
            dto.setReadReceiptStatus(receipt.getStatus());
            dto.setReadReceiptSender(receipt.getSenderEmail());
            dto.setReadReceiptRecipient(receipt.getRecipientEmail());
            if (receipt.getRespondedAt() != null) {
                dto.setReadReceiptRespondedAt(Date.from(receipt.getRespondedAt().atZone(ZoneId.systemDefault()).toInstant()));
            }
        });
    }

    public Optional<ReadReceipt> findReceiptBySendId(String sendId) {
        if (sendId == null) return Optional.empty();
        return readReceiptRepository.findBySendId(sendId);
    }

    public Optional<ReadReceipt> findReceiptByMessageId(String messageId) {
        if (messageId == null) return Optional.empty();
        return readReceiptRepository.findByMessageId(messageId);
    }

    public Optional<ReadReceipt> findReceiptById(String receiptId) {
        if (receiptId == null) return Optional.empty();
        return readReceiptRepository.findByReceiptId(receiptId);
    }
}
