package com.btctech.mailapp.service;

import com.btctech.mailapp.dto.SystemLabelSettingItemDto;
import com.btctech.mailapp.dto.SystemLabelSettingsRequest;
import com.btctech.mailapp.dto.SystemLabelSettingsResponse;
import com.btctech.mailapp.entity.SystemLabelSetting;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.exception.MailException;
import com.btctech.mailapp.repository.SystemLabelSettingRepository;
import com.btctech.mailapp.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SystemLabelSettingServiceTest {

    private List<SystemLabelSetting> store;
    private SystemLabelSettingService service;

    @BeforeEach
    void setUp() {
        store = new ArrayList<>();
        AtomicLong seq = new AtomicLong(1);

        SystemLabelSettingRepository settingRepo = (SystemLabelSettingRepository) Proxy.newProxyInstance(
                SystemLabelSettingRepository.class.getClassLoader(),
                new Class<?>[]{SystemLabelSettingRepository.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "findByUserId": {
                            Long uid = (Long) args[0];
                            return store.stream()
                                    .filter(s -> Objects.equals(s.getUserId(), uid))
                                    .collect(Collectors.toList());
                        }
                        case "findByUserIdAndLabelName": {
                            Long uid = (Long) args[0];
                            String name = (String) args[1];
                            return store.stream()
                                    .filter(s -> Objects.equals(s.getUserId(), uid) && Objects.equals(s.getLabelName(), name))
                                    .findFirst();
                        }
                        case "save": {
                            SystemLabelSetting s = (SystemLabelSetting) args[0];
                            return upsert(store, seq, s);
                        }
                        case "saveAll": {
                            @SuppressWarnings("unchecked")
                            Iterable<SystemLabelSetting> items = (Iterable<SystemLabelSetting>) args[0];
                            List<SystemLabelSetting> result = new ArrayList<>();
                            for (SystemLabelSetting item : items) {
                                result.add(upsert(store, seq, item));
                            }
                            return result;
                        }
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                }
        );

        User alice = new User();
        alice.setId(101L);
        alice.setEmail("alice@bnxmail.com");
        alice.setUsername("alice");

        User bob = new User();
        bob.setId(202L);
        bob.setEmail("bob@bnxmail.com");
        bob.setUsername("bob");

        List<User> users = List.of(alice, bob);
        UserRepository userRepo = (UserRepository) Proxy.newProxyInstance(
                UserRepository.class.getClassLoader(),
                new Class<?>[]{UserRepository.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "findByEmail": {
                            String email = (String) args[0];
                            return users.stream().filter(u -> email.equalsIgnoreCase(u.getEmail())).findFirst();
                        }
                        case "findByUsername": {
                            String username = (String) args[0];
                            return users.stream().filter(u -> username.equalsIgnoreCase(u.getUsername())).findFirst();
                        }
                        case "findById": {
                            Long id = (Long) args[0];
                            return users.stream().filter(u -> Objects.equals(u.getId(), id)).findFirst();
                        }
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                }
        );

        service = new SystemLabelSettingService(settingRepo, userRepo, null);
    }

    private static SystemLabelSetting upsert(List<SystemLabelSetting> store, AtomicLong seq, SystemLabelSetting entity) {
        if (entity.getId() == null) {
            boolean duplicate = store.stream().anyMatch(s ->
                    Objects.equals(s.getUserId(), entity.getUserId())
                            && Objects.equals(s.getLabelName(), entity.getLabelName()));
            if (duplicate) {
                throw new IllegalStateException("Duplicate (userId, labelName): " + entity.getLabelName());
            }
            entity.setId(seq.getAndIncrement());
            store.add(entity);
        }
        return entity;
    }

    @Test
    void getSettingsDefaultsAllSupportedLabelsToTrue() {
        SystemLabelSettingsResponse response = service.getSettings("alice@bnxmail.com");

        assertNotNull(response);
        assertEquals(SystemLabelSettingService.SUPPORTED_SYSTEM_LABELS.size(), response.getSettings().size());
        Map<String, Boolean> map = response.getSettings().stream()
                .collect(Collectors.toMap(SystemLabelSettingItemDto::getLabelName, SystemLabelSettingItemDto::getShowInLabelList));

        for (String expected : List.of("INBOX", "STARRED", "DRAFTS", "SENT", "SNOOZED", "IMPORTANT", "SPAM", "TRASH", "SCHEDULED", "ALL_MAIL")) {
            assertEquals(Boolean.TRUE, map.get(expected), "Expected default true for " + expected);
        }
    }

    @Test
    void updateSettingsPersistsOffAndOnUpsertWithoutDuplicates() {
        SystemLabelSettingsRequest offRequest = new SystemLabelSettingsRequest(List.of(
                new SystemLabelSettingItemDto("Draft", false),
                new SystemLabelSettingItemDto("SENT", false),
                new SystemLabelSettingItemDto("all mail", true)
        ));

        SystemLabelSettingsResponse response = service.updateSettings("alice@bnxmail.com", offRequest);

        assertEquals(3, store.size());
        Map<String, Boolean> resultMap = response.getSettings().stream()
                .collect(Collectors.toMap(SystemLabelSettingItemDto::getLabelName, SystemLabelSettingItemDto::getShowInLabelList));
        assertEquals(Boolean.FALSE, resultMap.get("DRAFTS"));
        assertEquals(Boolean.FALSE, resultMap.get("SENT"));
        assertEquals(Boolean.TRUE, resultMap.get("ALL_MAIL"));
        assertEquals(Boolean.TRUE, resultMap.get("INBOX"));

        // Re-enabling DRAFTS updates existing row in-place rather than inserting duplicate
        SystemLabelSettingsResponse reEnabled = service.updateSettings(
                "alice@bnxmail.com",
                new SystemLabelSettingsRequest(List.of(new SystemLabelSettingItemDto("DRAFTS", true)))
        );
        assertEquals(3, store.size());
        Map<String, Boolean> reEnabledMap = reEnabled.getSettings().stream()
                .collect(Collectors.toMap(SystemLabelSettingItemDto::getLabelName, SystemLabelSettingItemDto::getShowInLabelList));
        assertEquals(Boolean.TRUE, reEnabledMap.get("DRAFTS"));

        // Fresh GET confirms persisted DB state
        Map<String, Boolean> reloaded = service.getSettings("alice@bnxmail.com").getSettings().stream()
                .collect(Collectors.toMap(SystemLabelSettingItemDto::getLabelName, SystemLabelSettingItemDto::getShowInLabelList));
        assertEquals(Boolean.TRUE, reloaded.get("DRAFTS"));
        assertEquals(Boolean.FALSE, reloaded.get("SENT"));
    }

    @Test
    void settingsAreIsolatedPerUser() {
        service.updateSettings(
                "alice@bnxmail.com",
                new SystemLabelSettingsRequest(List.of(new SystemLabelSettingItemDto("TRASH", false)))
        );

        Map<String, Boolean> aliceMap = service.getSettings("alice@bnxmail.com").getSettings().stream()
                .collect(Collectors.toMap(SystemLabelSettingItemDto::getLabelName, SystemLabelSettingItemDto::getShowInLabelList));
        Map<String, Boolean> bobMap = service.getSettings("bob@bnxmail.com").getSettings().stream()
                .collect(Collectors.toMap(SystemLabelSettingItemDto::getLabelName, SystemLabelSettingItemDto::getShowInLabelList));

        assertEquals(Boolean.FALSE, aliceMap.get("TRASH"));
        assertEquals(Boolean.TRUE, bobMap.get("TRASH"));
    }

    @Test
    void rejectsInvalidLabelNamesBlankNamesConflictingDuplicatesAndNullVisibility() {
        assertThrows(MailException.class, () -> service.updateSettings(
                "alice@bnxmail.com",
                new SystemLabelSettingsRequest(List.of(new SystemLabelSettingItemDto("CUSTOM_WORK", false)))
        ));

        assertThrows(MailException.class, () -> service.updateSettings(
                "alice@bnxmail.com",
                new SystemLabelSettingsRequest(List.of(new SystemLabelSettingItemDto("   ", false)))
        ));

        assertThrows(MailException.class, () -> service.updateSettings(
                "alice@bnxmail.com",
                new SystemLabelSettingsRequest(List.of(new SystemLabelSettingItemDto("INBOX", null)))
        ));

        assertThrows(MailException.class, () -> service.updateSettings(
                "alice@bnxmail.com",
                new SystemLabelSettingsRequest(List.of(
                        new SystemLabelSettingItemDto("DRAFT", false),
                        new SystemLabelSettingItemDto("DRAFTS", true)
                ))
        ));

        assertTrue(store.isEmpty());
    }
}
