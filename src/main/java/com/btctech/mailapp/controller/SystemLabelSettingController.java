package com.btctech.mailapp.controller;

import com.btctech.mailapp.dto.ApiResponse;
import com.btctech.mailapp.dto.SystemLabelSettingsRequest;
import com.btctech.mailapp.dto.SystemLabelSettingsResponse;
import com.btctech.mailapp.service.SystemLabelSettingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/labels")
@RequiredArgsConstructor
public class SystemLabelSettingController {

    private final SystemLabelSettingService systemLabelSettingService;

    @GetMapping("/system-settings")
    public ResponseEntity<ApiResponse<SystemLabelSettingsResponse>> getSystemLabelSettings(
            Authentication authentication) {
        String identifier = requireAuthenticatedUser(authentication);
        SystemLabelSettingsResponse response = systemLabelSettingService.getSettings(identifier);
        return ResponseEntity.ok(ApiResponse.success(response, "System label settings fetched successfully"));
    }

    @PutMapping("/system-settings")
    public ResponseEntity<ApiResponse<SystemLabelSettingsResponse>> updateSystemLabelSettings(
            Authentication authentication,
            @Valid @RequestBody SystemLabelSettingsRequest request) {
        String identifier = requireAuthenticatedUser(authentication);
        SystemLabelSettingsResponse response = systemLabelSettingService.updateSettings(identifier, request);
        return ResponseEntity.ok(ApiResponse.success(response, "System label settings updated successfully"));
    }

    private String requireAuthenticatedUser(Authentication authentication) {
        if (authentication == null
                || authentication.getName() == null
                || authentication.getName().isBlank()
                || "anonymousUser".equalsIgnoreCase(authentication.getName())) {
            throw new AccessDeniedException("Authentication required");
        }
        return authentication.getName();
    }
}

