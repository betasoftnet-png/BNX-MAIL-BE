package com.btctech.mailapp.controller;

import com.btctech.mailapp.dto.CasboxArchiveRequest;
import com.btctech.mailapp.dto.CasboxMessageDto;
import com.btctech.mailapp.dto.CasboxSendRequest;
import com.btctech.mailapp.dto.CasboxStatusRequest;
import com.btctech.mailapp.service.CasboxService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/casbox")
@RequiredArgsConstructor
public class CasboxController {

    private final CasboxService casboxService;

    @PostMapping("/send")
    public ResponseEntity<CasboxMessageDto> sendMessage(
            @Valid @RequestBody CasboxSendRequest request,
            Authentication authentication) {
        String senderEmail = authentication.getName();
        return ResponseEntity.ok(casboxService.sendMessage(senderEmail, request));
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
}
