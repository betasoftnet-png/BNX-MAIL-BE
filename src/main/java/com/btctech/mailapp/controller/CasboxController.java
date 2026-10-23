package com.btctech.mailapp.controller;

import com.btctech.mailapp.dto.CasboxArchiveRequest;
import com.btctech.mailapp.dto.CasboxEditRequest;
import com.btctech.mailapp.dto.CasboxMessageDto;
import com.btctech.mailapp.dto.CasboxSendRequest;
import com.btctech.mailapp.dto.CasboxStatusRequest;
import com.btctech.mailapp.service.CasboxService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/casbox")
public class CasboxController {

    private final CasboxService casboxService;

    public CasboxController(CasboxService casboxService) {
        this.casboxService = casboxService;
    }

    @PostMapping("/send")
    public ResponseEntity<CasboxMessageDto> sendMessage(
            @Valid @RequestBody CasboxSendRequest request,
            Authentication authentication) {
        String senderEmail = authentication.getName();
        return ResponseEntity.ok(casboxService.sendMessage(senderEmail, request));
    }

    @GetMapping("/validate-recipient")
    public ResponseEntity<java.util.Map<String, Object>> validateRecipient(
            @RequestParam(required = false) String email,
            Authentication authentication) {
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        try {
            boolean valid = casboxService.isValidRecipient(email);
            result.put("valid", valid);
            result.put("email", email);
            if (!valid) {
                result.put("message", "Invalid email ID. This BNXmail account does not exist.");
            }
            return ResponseEntity.ok(result);
        } catch (Throwable t) {
            result.put("valid", true);
            result.put("email", email);
            return ResponseEntity.ok(result);
        }
    }

    @GetMapping("/thread/{contactEmail}")
    public ResponseEntity<List<CasboxMessageDto>> getThread(
            @PathVariable String contactEmail,
            Authentication authentication) {
        String userEmail = authentication.getName();
        return ResponseEntity.ok(casboxService.getThread(userEmail, contactEmail));
    }

    @GetMapping
    public ResponseEntity<List<CasboxMessageDto>> getAllMessages(Authentication authentication) {
        String userEmail = authentication.getName();
        return ResponseEntity.ok(casboxService.getAllMessages(userEmail));
    }

    @PatchMapping("/archive")
    public ResponseEntity<Void> updateArchiveStatus(
            @RequestBody CasboxArchiveRequest request,
            Authentication authentication) {
        String userEmail = authentication.getName();
        casboxService.updateArchiveStatus(request.getMessageIds(), request.getArchived(), userEmail);
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{id}/archive")
    public ResponseEntity<Void> archiveMessage(
            @PathVariable Long id,
            @RequestParam(defaultValue = "true") boolean archived,
            Authentication authentication) {
        String userEmail = authentication.getName();
        casboxService.updateArchiveStatus(List.of(id), archived, userEmail);
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/status")
    public ResponseEntity<Void> updateStatus(
            @Valid @RequestBody CasboxStatusRequest request,
            Authentication authentication) {
        String receiverEmail = authentication.getName();
        casboxService.updateStatus(request.getMessageIds(), request.getStatus(), receiverEmail);
        return ResponseEntity.ok().build();
    }
    
    @PostMapping("/delivered")
    public ResponseEntity<Void> markAsDelivered(Authentication authentication) {
        String receiverEmail = authentication.getName();
        casboxService.markUnseenAsDelivered(receiverEmail);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping({"/conversation/{contactEmail:.+}", "/conversation", "/messages/{contactEmail:.+}", "/{contactEmail:.+}"})
    public ResponseEntity<Void> deleteConversation(
            @PathVariable(required = false) String contactEmail,
            @RequestParam(required = false) String email,
            Authentication authentication) {
        String userEmail = authentication.getName();
        String targetContact = contactEmail != null ? contactEmail : email;
        if (targetContact == null || targetContact.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        casboxService.deleteConversation(userEmail, targetContact.trim());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/message/{id}")
    public ResponseEntity<Void> deleteMessage(
            @PathVariable Long id,
            Authentication authentication) {
        String userEmail = authentication.getName();
        casboxService.deleteMessage(id, userEmail);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/messages")
    public ResponseEntity<Void> deleteMessages(
            @RequestBody(required = false) List<Long> messageIds,
            @RequestParam(required = false) List<Long> ids,
            Authentication authentication) {
        String userEmail = authentication.getName();
        List<Long> targets = (messageIds != null && !messageIds.isEmpty()) ? messageIds : ids;
        if (targets != null && !targets.isEmpty()) {
            casboxService.deleteMessages(targets, userEmail);
        }
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{id}")
    public ResponseEntity<CasboxMessageDto> editMessage(
            @PathVariable Long id,
            @RequestBody CasboxEditRequest request,
            Authentication authentication) {
        String userEmail = authentication.getName();
        return ResponseEntity.ok(casboxService.editMessage(id, request, userEmail));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CasboxMessageDto> editMessagePut(
            @PathVariable Long id,
            @RequestBody CasboxEditRequest request,
            Authentication authentication) {
        String userEmail = authentication.getName();
        return ResponseEntity.ok(casboxService.editMessage(id, request, userEmail));
    }

    @PatchMapping("/{id}/edit")
    public ResponseEntity<CasboxMessageDto> editMessageExplicit(
            @PathVariable Long id,
            @RequestBody CasboxEditRequest request,
            Authentication authentication) {
        String userEmail = authentication.getName();
        return ResponseEntity.ok(casboxService.editMessage(id, request, userEmail));
    }
}
