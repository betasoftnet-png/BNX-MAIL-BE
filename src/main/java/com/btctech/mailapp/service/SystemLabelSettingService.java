package com.btctech.mailapp.service;

import com.btctech.mailapp.dto.SystemLabelSettingItemDto;
import com.btctech.mailapp.dto.SystemLabelSettingsRequest;
import com.btctech.mailapp.dto.SystemLabelSettingsResponse;
import com.btctech.mailapp.entity.SystemLabelSetting;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.exception.MailException;
import com.btctech.mailapp.repository.SystemLabelSettingRepository;
import com.btctech.mailapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SystemLabelSettingService {

    public static final List<String> SUPPORTED_SYSTEM_LABELS = List.of(
            "INBOX",
            "STARRED",
            "SNOOZED",
            "IMPORTANT",
            "SENT",
            "SCHEDULED",
            "DRAFTS",
            "ALL_MAIL",
            "SPAM",
            "TRASH"
    );

    private static final Map<String, String> LABEL_ALIASES = buildAliasMap();

    private final SystemLabelSettingRepository systemLabelSettingRepository;
    private final UserRepository userRepository;
    private final UserService userService;

    private static Map<String, String> buildAliasMap() {
        Map<String, String> aliases = new HashMap<>();
        for (String canonical : SUPPORTED_SYSTEM_LABELS) {
            aliases.put(canonical, canonical);
        }
        aliases.put("STAR", "STARRED");
        aliases.put("SNOOZE", "SNOOZED");
        aliases.put("SCHEDULE", "SCHEDULED");
        aliases.put("DRAFT", "DRAFTS");
        aliases.put("ALLMAIL", "ALL_MAIL");
        aliases.put("ALL", "ALL_MAIL");
        return Collections.unmodifiableMap(aliases);
    }

    public String normalizeLabelName(String rawLabelName) {
        if (rawLabelName == null || rawLabelName.isBlank()) {
            throw new MailException("System label name must not be blank");
        }
        String normalized = rawLabelName.trim()
                .replace('-', '_')
                .replaceAll("\\s+", "_")
                .toUpperCase(Locale.ROOT);
        String canonical = LABEL_ALIASES.get(normalized);
        if (canonical == null) {
            throw new MailException("Unsupported system label: " + rawLabelName.trim());
        }
        return canonical;
    }

    @Transactional(readOnly = true)
    public SystemLabelSettingsResponse getSettings(String userIdentifier) {
        Long userId = resolveUserId(userIdentifier);
        List<SystemLabelSetting> existing = systemLabelSettingRepository.findByUserId(userId);
        return buildResponse(existing);
    }

    @Transactional
    public SystemLabelSettingsResponse updateSettings(String userIdentifier, SystemLabelSettingsRequest request) {
        if (request == null || request.getSettings() == null || request.getSettings().isEmpty()) {
            throw new MailException("settings list must not be empty");
        }

        Long userId = resolveUserId(userIdentifier);

        Map<String, Boolean> validatedUpdates = new LinkedHashMap<>();
        for (SystemLabelSettingItemDto item : request.getSettings()) {
            if (item == null) {
                throw new MailException("System label setting item must not be null");
            }
            String canonicalName = normalizeLabelName(item.getLabelName());
            Boolean showInLabelList = item.getShowInLabelList();
            if (showInLabelList == null) {
                throw new MailException("showInLabelList is required for system label: " + canonicalName);
            }
            if (validatedUpdates.containsKey(canonicalName)
                    && !validatedUpdates.get(canonicalName).equals(showInLabelList)) {
                throw new MailException("Conflicting duplicate entries for system label: " + canonicalName);
            }
            validatedUpdates.put(canonicalName, showInLabelList);
        }

        List<SystemLabelSetting> currentSettings = systemLabelSettingRepository.findByUserId(userId);
        Map<String, SystemLabelSetting> existingByName = new HashMap<>();
        for (SystemLabelSetting setting : currentSettings) {
            if (setting != null && setting.getLabelName() != null) {
                String key = tryNormalizeOrUpper(setting.getLabelName());
                existingByName.putIfAbsent(key, setting);
            }
        }

        List<SystemLabelSetting> toSave = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : validatedUpdates.entrySet()) {
            String labelName = entry.getKey();
            Boolean show = entry.getValue();

            SystemLabelSetting entity = existingByName.get(labelName);
            if (entity == null) {
                entity = systemLabelSettingRepository
                        .findByUserIdAndLabelName(userId, labelName)
                        .orElseGet(() -> SystemLabelSetting.builder()
                                .userId(userId)
                                .labelName(labelName)
                                .build());
            } else {
                entity.setLabelName(labelName);
            }
            entity.setShowInLabelList(show);
            existingByName.put(labelName, entity);
            toSave.add(entity);
        }

        if (!toSave.isEmpty()) {
            systemLabelSettingRepository.saveAll(toSave);
        }

        log.debug("Updated {} system label visibility settings for userId={}", toSave.size(), userId);
        return buildResponse(existingByName.values());
    }

    private SystemLabelSettingsResponse buildResponse(Collection<SystemLabelSetting> savedSettings) {
        Map<String, Boolean> visibilityMap = new HashMap<>();
        if (savedSettings != null) {
            for (SystemLabelSetting setting : savedSettings) {
                if (setting != null && setting.getLabelName() != null) {
                    String canonical = tryNormalizeOrUpper(setting.getLabelName());
                    boolean show = setting.getShowInLabelList() == null || setting.getShowInLabelList();
                    visibilityMap.put(canonical, show);
                }
            }
        }

        List<SystemLabelSettingItemDto> items = new ArrayList<>(SUPPORTED_SYSTEM_LABELS.size());
        for (String canonicalLabel : SUPPORTED_SYSTEM_LABELS) {
            items.add(SystemLabelSettingItemDto.builder()
                    .labelName(canonicalLabel)
                    .showInLabelList(visibilityMap.getOrDefault(canonicalLabel, Boolean.TRUE))
                    .build());
        }
        return SystemLabelSettingsResponse.builder()
                .settings(items)
                .build();
    }

    private String tryNormalizeOrUpper(String labelName) {
        try {
            return normalizeLabelName(labelName);
        } catch (MailException ex) {
            return labelName.trim().toUpperCase(Locale.ROOT);
        }
    }

    private Long resolveUserId(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new MailException("Authentication required");
        }
        String clean = identifier.trim();
        User resolved = null;
        if (userService != null) {
            try {
                resolved = userService.getUserByEmailOrUsername(clean);
            } catch (Exception ignored) {
            }
        }

        if (resolved == null) {
            resolved = userRepository.findByEmail(clean)
                    .or(() -> userRepository.findByUsername(clean))
                    .orElse(null);
        }

        if (resolved == null && clean.matches("\\d+")) {
            try {
                resolved = userRepository.findById(Long.parseLong(clean)).orElse(null);
            } catch (NumberFormatException ignored) {
            }
        }

        if (resolved == null || resolved.getId() == null) {
            throw new MailException("Authenticated user not found");
        }
        return resolved.getId();
    }
}
