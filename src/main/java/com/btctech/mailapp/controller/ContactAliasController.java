package com.btctech.mailapp.controller;

import com.btctech.mailapp.dto.ContactAliasDto;
import com.btctech.mailapp.dto.ContactAliasRequest;
import com.btctech.mailapp.service.ContactAliasService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/contact-aliases")
@RequiredArgsConstructor
public class ContactAliasController {

    private final ContactAliasService contactAliasService;

    @PutMapping("/{contactUserId}")
    public ResponseEntity<ContactAliasDto> setAlias(
            @PathVariable String contactUserId,
            @Valid @RequestBody ContactAliasRequest request,
            Authentication authentication) {
        String ownerIdentifier = authentication.getName();
        ContactAliasDto dto = contactAliasService.setAlias(ownerIdentifier, contactUserId, request.getCustomName());
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/{contactUserId}")
    public ResponseEntity<ContactAliasDto> getAlias(
            @PathVariable String contactUserId,
            Authentication authentication) {
        String ownerIdentifier = authentication.getName();
        ContactAliasDto dto = contactAliasService.getAlias(ownerIdentifier, contactUserId);
        if (dto == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(dto);
    }

    @DeleteMapping("/{contactUserId}")
    public ResponseEntity<Void> deleteAlias(
            @PathVariable String contactUserId,
            Authentication authentication) {
        String ownerIdentifier = authentication.getName();
        contactAliasService.deleteAlias(ownerIdentifier, contactUserId);
        return ResponseEntity.ok().build();
    }

    @GetMapping
    public ResponseEntity<List<ContactAliasDto>> getAllAliases(Authentication authentication) {
        String ownerIdentifier = authentication.getName();
        return ResponseEntity.ok(contactAliasService.getAllAliases(ownerIdentifier));
    }
}
