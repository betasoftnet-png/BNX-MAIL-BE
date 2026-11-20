package com.btctech.mailapp.controller;

import com.btctech.mailapp.dto.ApiResponse;
import com.btctech.mailapp.entity.ReadReceipt;
import com.btctech.mailapp.exception.MailException;
import com.btctech.mailapp.service.ReadReceiptService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/mail/read-receipt")
@RequiredArgsConstructor
public class ReadReceiptController {

    private final ReadReceiptService readReceiptService;

    @Data
    public static class ReceiptResponseRequest {
        private String action; // ACCEPT or DECLINE
        private String reason;
    }

    @Data
    public static class ReceiptStatusUpdateRequest {
        private String status; // UNSUPPORTED or FAILED
        private String reason;
    }

    /**
     * Recipient responds to Read Receipt request (ACCEPT or DECLINE).
     * Atomic transition from PENDING -> READ or DECLINED.
     * Prevents duplicate receipt submissions.
     */
    @PostMapping("/{receiptId}/respond")
    public ResponseEntity<ApiResponse<ReadReceipt>> respondToReceipt(
            @PathVariable String receiptId,
            @RequestBody(required = false) ReceiptResponseRequest request,
            @RequestHeader(value = "User-Agent", required = false) String userAgent,
            Authentication authentication) {

        String responderEmail = authentication != null ? authentication.getName() : "recipient";
        String action = request != null && request.getAction() != null ? request.getAction() : "ACCEPT";
        String reason = request != null ? request.getReason() : null;

        log.info("Read receipt response received for receiptId: {}, action: {}, by: {}", receiptId, action, responderEmail);

        try {
            ReadReceipt receipt = readReceiptService.respondToReceipt(receiptId, responderEmail, action, userAgent, reason);
            String msg = "READ".equalsIgnoreCase(receipt.getStatus())
                    ? "Read receipt sent successfully"
                    : "Read receipt request declined";
            return ResponseEntity.ok(ApiResponse.success(receipt, msg));
        } catch (MailException ex) {
            log.warn("Read receipt response failed: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(ex.getMessage()));
        }
    }

    /**
     * Updates receipt status to UNSUPPORTED or FAILED.
     */
    @PostMapping("/{receiptId}/status")
    public ResponseEntity<ApiResponse<ReadReceipt>> updateStatus(
            @PathVariable String receiptId,
            @RequestBody ReceiptStatusUpdateRequest request) {

        String status = request != null && request.getStatus() != null ? request.getStatus() : "FAILED";
        String reason = request != null ? request.getReason() : null;

        try {
            ReadReceipt receipt = readReceiptService.updateReceiptStatus(receiptId, status, reason);
            return ResponseEntity.ok(ApiResponse.success(receipt, "Status updated to " + receipt.getStatus()));
        } catch (MailException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(ex.getMessage()));
        }
    }

    /**
     * Sender inspects Read Receipt record.
     * Protected: strictly for eligible Business Accounts owning this receipt.
     */
    @GetMapping("/{receiptId}")
    public ResponseEntity<ApiResponse<ReadReceipt>> getReceiptStatus(
            @PathVariable String receiptId,
            Authentication authentication) {

        if (authentication == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Unauthorized"));
        }

        String userEmail = authentication.getName();
        if (!readReceiptService.isBusinessAccount(userEmail)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("Read receipt feature is only available to Business Accounts"));
        }

        try {
            ReadReceipt receipt = readReceiptService.getReceiptForSender(receiptId, userEmail);
            return ResponseEntity.ok(ApiResponse.success(receipt, "Read receipt status retrieved"));
        } catch (MailException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(ex.getMessage()));
        }
    }

    /**
     * Sender queries Read Receipt status by sendId, messageId, or sentUid.
     * Available ONLY to Business Accounts.
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<ReadReceipt>> queryReceiptStatus(
            @RequestParam(required = false) String sendId,
            @RequestParam(required = false) String messageId,
            @RequestParam(required = false) String receiptId,
            Authentication authentication) {

        if (authentication == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Unauthorized"));
        }

        String userEmail = authentication.getName();
        if (!readReceiptService.isBusinessAccount(userEmail)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("Read receipt feature is only available to Business Accounts"));
        }

        Optional<ReadReceipt> receiptOpt = Optional.empty();
        if (receiptId != null && !receiptId.trim().isEmpty()) {
            receiptOpt = readReceiptService.findReceiptById(receiptId.trim());
        } else if (sendId != null && !sendId.trim().isEmpty()) {
            receiptOpt = readReceiptService.findReceiptBySendId(sendId.trim());
        } else if (messageId != null && !messageId.trim().isEmpty()) {
            receiptOpt = readReceiptService.findReceiptByMessageId(messageId.trim());
        }

        if (receiptOpt.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.success(null, "No read receipt found"));
        }

        ReadReceipt receipt = receiptOpt.get();
        if (!receipt.getSenderEmail().equalsIgnoreCase(userEmail)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("You are not authorized to view this read receipt"));
        }

        return ResponseEntity.ok(ApiResponse.success(receipt, "Read receipt status retrieved"));
    }
}
