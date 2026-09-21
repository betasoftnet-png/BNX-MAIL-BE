package com.btctech.mailapp.controller;

import com.btctech.mailapp.dto.ApiResponse;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.entity.MailAccount;
import com.btctech.mailapp.entity.UserSettings;
import com.btctech.mailapp.service.UserService;
import com.btctech.mailapp.config.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final com.btctech.mailapp.repository.MailAccountRepository mailAccountRepository;
    private final JwtUtil jwtUtil;
    private final com.btctech.mailapp.repository.BusinessProfileRepository businessProfileRepository;
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCurrentUser(
            @RequestHeader("Authorization") String authHeader) {
        
        String token = authHeader.replace("Bearer ", "");
        String identifier = jwtUtil.extractEmail(token);
        User user = userService.getUserByEmailOrUsername(identifier);
        
        // Find primary BNX Mail
        String bnxEmail = user.getEmail();
        var primaryAccount = mailAccountRepository.findByUserIdAndIsPrimary(user.getId(), true)
                .or(() -> mailAccountRepository.findByUserId(user.getId()).stream().findFirst());
        
        if (primaryAccount.isPresent()) {
            bnxEmail = primaryAccount.get().getEmail();
        }

        String fullName = (user.getFirstName() != null ? user.getFirstName() : "") + 
                         (user.getLastName() != null ? " " + user.getLastName() : "");
        fullName = fullName.trim();
        if (fullName.isEmpty()) fullName = user.getUsername();

        Map<String, Object> data = new HashMap<>();
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("firstName", user.getFirstName());
        data.put("lastName", user.getLastName());
        data.put("fullName", fullName);
        data.put("email", bnxEmail != null ? bnxEmail : user.getUsername() + "@bnxmail.com");
        data.put("recoveryEmail", user.getRecoveryEmail());
        data.put("profilePicture", user.getProfilePicture());
        data.put("profilePictureUrl", user.getProfilePicture() != null ? "/api/users/profile-picture/" + user.getUsername() : null);
        data.put("phoneNumber", user.getPhoneNumber());
        data.put("dob", user.getDob());
        data.put("nickname", user.getNickname());
        data.put("displayName", user.getDisplayName());
        data.put("gender", user.getGender());
        data.put("homeAddress", user.getHomeAddress());
        data.put("workAddress", user.getWorkAddress());
        data.put("occupation", user.getOccupation());
        data.put("bio", user.getBio());
        data.put("accountType", user.getAccountType());
        data.put("isPrimary", primaryAccount.isPresent() && primaryAccount.get().getIsPrimary());
        data.put("role", user.getRole());
        data.put("active", user.getActive());
        data.put("approved", user.getApproved());
        data.put("createdAt", user.getCreatedAt());
        data.put("updatedAt", user.getUpdatedAt());
        data.put("lastLogin", user.getLastLogin());
        data.put("twoFactorEnabled", user.getTwoFactorEnabled());
        
        if (Boolean.TRUE.equals(user.getIsSubId()) && user.getParent() != null) {
            data.put("isSubId", true);
            String parentEmail = user.getParent().getEmail();
            if (parentEmail == null || !parentEmail.contains("@")) {
                parentEmail = user.getParent().getUsername() + "@bnxmail.com";
            }
            data.put("parentAccount", parentEmail);
        } else {
            data.put("isSubId", false);
        }
        
        boolean onboarded = true;
        if (user.getAccountType() == com.btctech.mailapp.entity.AccountType.BUSINESS) {
            onboarded = businessProfileRepository.findByUserId(user.getId())
                    .map(com.btctech.mailapp.entity.BusinessProfile::getOnboarded)
                    .orElse(false);
        }
        data.put("onboarded", onboarded);

        // Add storage info
        UserSettings settings = userService.getSettings(user);
        List<MailAccount> allAccounts = mailAccountRepository.findByUserId(user.getId());
        long totalUsed = allAccounts.stream().mapToLong(MailAccount::getStorageUsed).sum();
        
        data.put("storageUsed", totalUsed);
        data.put("storageLimit", settings.getStorageLimit());
        
        if (user.getOrganization() != null) {
            Map<String, Object> org = new HashMap<>();
            org.put("id", user.getOrganization().getId());
            org.put("name", user.getOrganization().getName());
            data.put("organization", org);
        }
        
        return ResponseEntity.ok(ApiResponse.success(data, "User profile retrieved successfully"));
    }

    /**
     * Parent Approval API
     * authenticated endpoint for parents to approve children
     */
    @PatchMapping("/{id}/approve")
    public ResponseEntity<ApiResponse<Map<String, Object>>> approveChild(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id) {
        
        String token = authHeader.replace("Bearer ", "");
        String identifier = jwtUtil.extractEmail(token);
        
        // Identify caller (Parent)
        User parent = userService.getUserByEmailOrUsername(identifier);
        
        log.info("Approval request for child userId: {} from parent: {}", id, parent.getUsername());
        
        User approvedChild = userService.approveChild(parent, id);
        
        Map<String, Object> data = new HashMap<>();
        data.put("userId", approvedChild.getId());
        data.put("username", approvedChild.getUsername());
        data.put("approved", approvedChild.getApproved());
        data.put("approvedAt", approvedChild.getApprovedAt());
        
        return ResponseEntity.ok(ApiResponse.success(data, "Child account approved successfully"));
    }

    @GetMapping("/settings")
    public ResponseEntity<ApiResponse<com.btctech.mailapp.dto.UserSettingsDTO>> getSettings(
            @RequestHeader("Authorization") String authHeader) {
        
        String token = authHeader.replace("Bearer ", "");
        String identifier = jwtUtil.extractEmail(token);
        User user = userService.getUserByEmailOrUsername(identifier);
        
        com.btctech.mailapp.entity.UserSettings settings = userService.getSettings(user);
        
        com.btctech.mailapp.dto.UserSettingsDTO dto = com.btctech.mailapp.dto.UserSettingsDTO.builder()
                .phoneNumber(settings.getPhoneNumber())
                .location(settings.getLocation())
                .jobTitle(settings.getJobTitle())
                .inboxNotifications(settings.getInboxNotifications())
                .sentNotifications(settings.getSentNotifications())
                .starredNotifications(settings.getStarredNotifications())
                .snoozedNotifications(settings.getSnoozedNotifications())
                .soundEnabled(settings.getSoundEnabled())
                .vibrationEnabled(settings.getVibrationEnabled())
                .quietHoursEnabled(settings.getQuietHoursEnabled())
                .quietHoursStart(settings.getQuietHoursStart())
                .quietHoursEnd(settings.getQuietHoursEnd())
                .themeMode(settings.getThemeMode())
                .accentColor(settings.getAccentColor())
                .fontSize(settings.getFontSize())
                .density(settings.getDensity())
                .profilePictureUrl(user.getProfilePicture() != null ? "/api/users/profile-picture/" + user.getUsername() : null)
                .storageLimit(settings.getStorageLimit())
                .undoSendDelay(settings.getUndoSendDelay())
                .spellingCheckEnabled(settings.getSpellingCheckEnabled())
                .grammarCheckEnabled(settings.getGrammarCheckEnabled())
                .autoCorrectEnabled(settings.getAutoCorrectEnabled())
                .smartComposeEnabled(settings.getSmartComposeEnabled())
                .readingPaneMode(settings.getReadingPaneMode())
                .twoFactorEnabled(user.getTwoFactorEnabled())
                .biometricsEnabled(settings.getBiometricsEnabled())
                .language(settings.getLanguage())
                .fontFamily(settings.getFontFamily())
                .textStyleFontSize(settings.getTextStyleFontSize())
                .textColor(settings.getTextColor())
                .casboxAccepted(settings.getCasboxAccepted())
                .build();
                
        return ResponseEntity.ok(ApiResponse.success(dto, "Settings retrieved successfully"));
    }

    @PatchMapping("/settings")
    public ResponseEntity<ApiResponse<com.btctech.mailapp.dto.UserSettingsDTO>> updateSettings(
            HttpServletRequest request,
            @RequestHeader("Authorization") String authHeader,
            @RequestBody com.btctech.mailapp.dto.UserSettingsDTO settingsUpdate) {
        
        String token = authHeader.replace("Bearer ", "");
        String identifier = jwtUtil.extractEmail(token);
        User user = userService.getUserByEmailOrUsername(identifier);
        
        com.btctech.mailapp.entity.UserSettings update = com.btctech.mailapp.entity.UserSettings.builder()
                .phoneNumber(settingsUpdate.getPhoneNumber())
                .location(settingsUpdate.getLocation())
                .jobTitle(settingsUpdate.getJobTitle())
                .inboxNotifications(settingsUpdate.getInboxNotifications())
                .sentNotifications(settingsUpdate.getSentNotifications())
                .starredNotifications(settingsUpdate.getStarredNotifications())
                .snoozedNotifications(settingsUpdate.getSnoozedNotifications())
                .soundEnabled(settingsUpdate.getSoundEnabled())
                .vibrationEnabled(settingsUpdate.getVibrationEnabled())
                .quietHoursEnabled(settingsUpdate.getQuietHoursEnabled())
                .quietHoursStart(settingsUpdate.getQuietHoursStart())
                .quietHoursEnd(settingsUpdate.getQuietHoursEnd())
                .themeMode(settingsUpdate.getThemeMode())
                .accentColor(settingsUpdate.getAccentColor())
                .fontSize(settingsUpdate.getFontSize())
                .density(settingsUpdate.getDensity())
                .undoSendDelay(settingsUpdate.getUndoSendDelay())
                .spellingCheckEnabled(settingsUpdate.getSpellingCheckEnabled())
                .grammarCheckEnabled(settingsUpdate.getGrammarCheckEnabled())
                .autoCorrectEnabled(settingsUpdate.getAutoCorrectEnabled())
                .smartComposeEnabled(settingsUpdate.getSmartComposeEnabled())
                .readingPaneMode(settingsUpdate.getReadingPaneMode())
                .twoFactorEnabled(settingsUpdate.getTwoFactorEnabled())
                .biometricsEnabled(settingsUpdate.getBiometricsEnabled())
                .language(settingsUpdate.getLanguage())
                .fontFamily(settingsUpdate.getFontFamily())
                .textStyleFontSize(settingsUpdate.getTextStyleFontSize())
                .textColor(settingsUpdate.getTextColor())
                .casboxAccepted(settingsUpdate.getCasboxAccepted())
                .build();
                
        com.btctech.mailapp.entity.UserSettings saved = userService.updateSettings(user, update);
        
        // Log activity
        userService.logActivity(user, "Settings Updated", "Updated security/appearance settings", 
            request.getHeader("X-Forwarded-For"), request.getHeader("X-Device-Name"));
        
        // Re-build DTO
        com.btctech.mailapp.dto.UserSettingsDTO responseDto = com.btctech.mailapp.dto.UserSettingsDTO.builder()
                .phoneNumber(saved.getPhoneNumber())
                .location(saved.getLocation())
                .jobTitle(saved.getJobTitle())
                .inboxNotifications(saved.getInboxNotifications())
                .sentNotifications(saved.getSentNotifications())
                .starredNotifications(saved.getStarredNotifications())
                .snoozedNotifications(saved.getSnoozedNotifications())
                .soundEnabled(saved.getSoundEnabled())
                .vibrationEnabled(saved.getVibrationEnabled())
                .quietHoursEnabled(saved.getQuietHoursEnabled())
                .quietHoursStart(saved.getQuietHoursStart())
                .quietHoursEnd(saved.getQuietHoursEnd())
                .themeMode(saved.getThemeMode())
                .accentColor(saved.getAccentColor())
                .fontSize(saved.getFontSize())
                .density(saved.getDensity())
                .profilePictureUrl(user.getProfilePicture() != null ? "/api/users/profile-picture/" + user.getUsername() : null)
                .storageLimit(saved.getStorageLimit())
                .undoSendDelay(saved.getUndoSendDelay())
                .spellingCheckEnabled(saved.getSpellingCheckEnabled())
                .grammarCheckEnabled(saved.getGrammarCheckEnabled())
                .autoCorrectEnabled(saved.getAutoCorrectEnabled())
                .smartComposeEnabled(saved.getSmartComposeEnabled())
                .readingPaneMode(saved.getReadingPaneMode())
                .twoFactorEnabled(saved.getTwoFactorEnabled())
                .biometricsEnabled(saved.getBiometricsEnabled())
                .language(saved.getLanguage())
                .fontFamily(saved.getFontFamily())
                .textStyleFontSize(saved.getTextStyleFontSize())
                .textColor(saved.getTextColor())
                .casboxAccepted(saved.getCasboxAccepted())
                .build();
                
        return ResponseEntity.ok(ApiResponse.success(responseDto, "Settings updated successfully"));
    }

    @GetMapping("/activity-logs")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getActivityLogs(
            @RequestHeader("Authorization") String authHeader) {
        
        String token = authHeader.replace("Bearer ", "");
        String identifier = jwtUtil.extractEmail(token);
        User user = userService.getUserByEmailOrUsername(identifier);
        
        List<com.btctech.mailapp.entity.ActivityLog> logs = userService.getActivityLogs(user);
        
        List<Map<String, Object>> result = logs.stream().map(l -> {
            Map<String, Object> map = new HashMap<>();
            map.put("activity", l.getActivity());
            map.put("details", l.getDetails());
            map.put("timestamp", l.getTimestamp().toString());
            map.put("ipAddress", l.getIpAddress());
            map.put("deviceName", l.getDeviceName());
            return map;
        }).collect(java.util.stream.Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(result, "Activity logs retrieved successfully"));
    }

    @GetMapping("/recovery")
    public ResponseEntity<ApiResponse<Map<String, String>>> getRecoveryInfo(
            @RequestHeader("Authorization") String authHeader) {
        
        String token = authHeader.replace("Bearer ", "");
        String identifier = jwtUtil.extractEmail(token);
        User user = userService.getUserByEmailOrUsername(identifier);
        
        Map<String, String> data = new HashMap<>();
        data.put("recoveryEmail", user.getRecoveryEmail());
        data.put("phoneNumber", user.getPhoneNumber());
        
        return ResponseEntity.ok(ApiResponse.success(data, "Recovery info retrieved successfully"));
    }

    @PatchMapping("/recovery")
    public ResponseEntity<ApiResponse<Void>> updateRecoveryInfo(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody com.btctech.mailapp.dto.RecoveryInfoDTO recoveryInfo) {
        
        String token = authHeader.replace("Bearer ", "");
        String identifier = jwtUtil.extractEmail(token);
        User user = userService.getUserByEmailOrUsername(identifier);
        
        userService.updateRecoveryInfo(user, recoveryInfo.getRecoveryEmail(), recoveryInfo.getPhoneNumber());
        
        return ResponseEntity.ok(ApiResponse.success(null, "Recovery info updated successfully"));
    }

    @PatchMapping("/profile")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateProfile(
            HttpServletRequest request,
            @RequestHeader("Authorization") String authHeader,
            @RequestBody com.btctech.mailapp.dto.UserProfileDTO profileDto) {
        
        String token = authHeader.replace("Bearer ", "");
        String identifier = jwtUtil.extractEmail(token);
        User user = userService.getUserByEmailOrUsername(identifier);
        
        User updatedUser = userService.updateProfile(user, profileDto);
        
        // Log activity
        userService.logActivity(updatedUser, "Profile Updated", "Updated personal info and contact details", 
            request.getHeader("X-Forwarded-For"), request.getHeader("X-Device-Name"));
        
        // Return updated data
        String fullName = (updatedUser.getFirstName() != null ? updatedUser.getFirstName() : "") + 
                         (updatedUser.getLastName() != null ? " " + updatedUser.getLastName() : "");
        fullName = fullName.trim();
        if (fullName.isEmpty()) fullName = updatedUser.getUsername();

        Map<String, Object> data = new HashMap<>();
        data.put("id", updatedUser.getId());
        data.put("firstName", updatedUser.getFirstName());
        data.put("lastName", updatedUser.getLastName());
        data.put("fullName", fullName);
        data.put("recoveryEmail", updatedUser.getRecoveryEmail());
        data.put("phoneNumber", updatedUser.getPhoneNumber());
        data.put("dob", updatedUser.getDob());
        data.put("nickname", updatedUser.getNickname());
        data.put("displayName", updatedUser.getDisplayName());
        data.put("gender", updatedUser.getGender());
        data.put("homeAddress", updatedUser.getHomeAddress());
        data.put("workAddress", updatedUser.getWorkAddress());
        data.put("occupation", updatedUser.getOccupation());
        data.put("bio", updatedUser.getBio());

        return ResponseEntity.ok(ApiResponse.success(data, "Profile updated successfully"));
    }
}
