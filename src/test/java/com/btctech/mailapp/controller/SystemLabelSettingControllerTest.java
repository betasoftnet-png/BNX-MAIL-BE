package com.btctech.mailapp.controller;

import com.btctech.mailapp.dto.SystemLabelSettingItemDto;
import com.btctech.mailapp.dto.SystemLabelSettingsRequest;
import com.btctech.mailapp.entity.SystemLabelSetting;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.exception.GlobalExceptionHandler;
import com.btctech.mailapp.repository.SystemLabelSettingRepository;
import com.btctech.mailapp.repository.UserRepository;
import com.btctech.mailapp.service.SystemLabelSettingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SystemLabelSettingControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private UsernamePasswordAuthenticationToken aliceAuth;
    private UsernamePasswordAuthenticationToken bobAuth;

    @BeforeEach
    void setUp() {
        List<SystemLabelSetting> store = new ArrayList<>();
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
                        case "saveAll": {
                            @SuppressWarnings("unchecked")
                            Iterable<SystemLabelSetting> items = (Iterable<SystemLabelSetting>) args[0];
                            List<SystemLabelSetting> saved = new ArrayList<>();
                            for (SystemLabelSetting item : items) {
                                if (item.getId() == null) {
                                    item.setId(seq.getAndIncrement());
                                    store.add(item);
                                }
                                saved.add(item);
                            }
                            return saved;
                        }
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                }
        );

        User alice = new User();
        alice.setId(1L);
        alice.setEmail("alice@bnxmail.com");
        alice.setUsername("alice");

        User bob = new User();
        bob.setId(2L);
        bob.setEmail("bob@bnxmail.com");
        bob.setUsername("bob");

        List<User> users = List.of(alice, bob);
        UserRepository userRepo = (UserRepository) Proxy.newProxyInstance(
                UserRepository.class.getClassLoader(),
                new Class<?>[]{UserRepository.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "findByEmail":
                            return users.stream().filter(u -> ((String) args[0]).equalsIgnoreCase(u.getEmail())).findFirst();
                        case "findByUsername":
                            return users.stream().filter(u -> ((String) args[0]).equalsIgnoreCase(u.getUsername())).findFirst();
                        case "findById":
                            return users.stream().filter(u -> Objects.equals(u.getId(), args[0])).findFirst();
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                }
        );

        SystemLabelSettingService service = new SystemLabelSettingService(settingRepo, userRepo, null);
        SystemLabelSettingController controller = new SystemLabelSettingController(service);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        aliceAuth = new UsernamePasswordAuthenticationToken("alice@bnxmail.com", null, List.of());
        bobAuth = new UsernamePasswordAuthenticationToken("bob@bnxmail.com", null, List.of());
    }

    @Test
    void getAndPutRoundTripOffAndOnPersistsAndIsolatesPerUser() throws Exception {
        // Default GET -> INBOX=true, DRAFTS=true
        mockMvc.perform(get("/api/labels/system-settings").principal(aliceAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.settings[?(@.labelName=='DRAFTS')].showInLabelList", hasItem(true)))
                .andExpect(jsonPath("$.data.settings[?(@.labelName=='IMPORTANT')].showInLabelList", hasItem(true)));

        // Alice turns DRAFTS OFF and SPAM OFF
        SystemLabelSettingsRequest offRequest = new SystemLabelSettingsRequest(List.of(
                new SystemLabelSettingItemDto("DRAFTS", false),
                new SystemLabelSettingItemDto("SPAM", false)
        ));

        mockMvc.perform(put("/api/labels/system-settings")
                        .principal(aliceAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(offRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.settings[?(@.labelName=='DRAFTS')].showInLabelList", hasItem(false)))
                .andExpect(jsonPath("$.data.settings[?(@.labelName=='SPAM')].showInLabelList", hasItem(false)));

        // Alice reloads -> stays false; Bob reloads -> still true
        mockMvc.perform(get("/api/labels/system-settings").principal(aliceAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.settings[?(@.labelName=='DRAFTS')].showInLabelList", hasItem(false)));

        mockMvc.perform(get("/api/labels/system-settings").principal(bobAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.settings[?(@.labelName=='DRAFTS')].showInLabelList", hasItem(true)))
                .andExpect(jsonPath("$.data.settings[?(@.labelName=='SPAM')].showInLabelList", hasItem(true)));

        // Alice turns DRAFTS back ON
        SystemLabelSettingsRequest onRequest = new SystemLabelSettingsRequest(List.of(
                new SystemLabelSettingItemDto("DRAFTS", true)
        ));
        mockMvc.perform(put("/api/labels/system-settings")
                        .principal(aliceAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(onRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.settings[?(@.labelName=='DRAFTS')].showInLabelList", hasItem(true)));
    }

    @Test
    void updateSystemLabelSettingsReturns400OnEmptyOrInvalidRequest() throws Exception {
        mockMvc.perform(put("/api/labels/system-settings")
                        .principal(aliceAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        SystemLabelSettingsRequest invalidRequest = new SystemLabelSettingsRequest(List.of(
                new SystemLabelSettingItemDto("UNKNOWN_X", false)
        ));

        mockMvc.perform(put("/api/labels/system-settings")
                        .principal(aliceAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Unsupported system label: UNKNOWN_X"));
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/labels/system-settings"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }
}
