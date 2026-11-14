package com.btctech.mailapp.service;

import com.btctech.mailapp.entity.MailAccount;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.exception.MailException;
import com.btctech.mailapp.repository.ActivityLogRepository;
import com.btctech.mailapp.repository.MailAccountRepository;
import com.btctech.mailapp.repository.UserRepository;
import com.btctech.mailapp.repository.UserSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private MailAccountRepository mailAccountRepository;
    private UserSettingsRepository userSettingsRepository;
    private ActivityLogRepository activityLogRepository;
    private SessionService sessionService;

    private UserService userService;

    private User sampleUser;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        mailAccountRepository = mock(MailAccountRepository.class);
        userSettingsRepository = mock(UserSettingsRepository.class);
        activityLogRepository = mock(ActivityLogRepository.class);
        sessionService = mock(SessionService.class);

        userService = new UserService(
                userRepository,
                passwordEncoder,
                mailAccountRepository,
                userSettingsRepository,
                activityLogRepository,
                Collections.emptyList(),
                sessionService
        );

        sampleUser = new User();
        sampleUser.setId(10L);
        sampleUser.setUsername("dilliprasath1225");
        sampleUser.setEmail("dilliprasath1225@gmail.com");
        sampleUser.setRecoveryEmail("dilliprasath1225@gmail.com");
    }

    @Test
    @DisplayName("1. getUserByEmailOrUsername finds user by personal email (case-insensitive & trimmed)")
    void testGetUserByEmailOrUsername_DirectEmailMatch() {
        when(userRepository.findByIdentifierIgnoreCase("dilliprasath1225@gmail.com"))
                .thenReturn(List.of(sampleUser));

        User result = userService.getUserByEmailOrUsername("  DILLIPRASATH1225@GMAIL.COM  ");

        assertNotNull(result);
        assertEquals("dilliprasath1225", result.getUsername());
        assertEquals("dilliprasath1225@gmail.com", result.getEmail());
    }

    @Test
    @DisplayName("2. getUserByEmailOrUsername finds user by recovery email")
    void testGetUserByEmailOrUsername_RecoveryEmailMatch() {
        User userWithDifferentEmail = new User();
        userWithDifferentEmail.setId(11L);
        userWithDifferentEmail.setUsername("dilliprasath");
        userWithDifferentEmail.setEmail("dillip@bnxmail.com");
        userWithDifferentEmail.setRecoveryEmail("dilliprasath1225@gmail.com");

        when(userRepository.findByIdentifierIgnoreCase("dilliprasath1225@gmail.com"))
                .thenReturn(List.of(userWithDifferentEmail));

        User result = userService.getUserByEmailOrUsername("dilliprasath1225@gmail.com");

        assertNotNull(result);
        assertEquals("dilliprasath", result.getUsername());
        assertEquals("dilliprasath1225@gmail.com", result.getRecoveryEmail());
    }

    @Test
    @DisplayName("3. getUserByEmailOrUsername falls back to MailAccount email")
    void testGetUserByEmailOrUsername_MailAccountEmailMatch() {
        when(userRepository.findByIdentifierIgnoreCase("dillip@bnxmail.com"))
                .thenReturn(Collections.emptyList());
        when(userRepository.findByMailAccountEmailIgnoreCase("dillip@bnxmail.com"))
                .thenReturn(Optional.of(sampleUser));

        User result = userService.getUserByEmailOrUsername("dillip@bnxmail.com");

        assertNotNull(result);
        assertEquals("dilliprasath1225", result.getUsername());
    }

    @Test
    @DisplayName("4. getUserByEmailOrUsername falls back to matching username prefix when identifier contains @")
    void testGetUserByEmailOrUsername_UsernamePrefixFallback() {
        when(userRepository.findByIdentifierIgnoreCase("dilliprasath1225@gmail.com"))
                .thenReturn(Collections.emptyList());
        when(userRepository.findByMailAccountEmailIgnoreCase("dilliprasath1225@gmail.com"))
                .thenReturn(Optional.empty());
        when(mailAccountRepository.findByEmail("dilliprasath1225@gmail.com"))
                .thenReturn(Optional.empty());
        when(userRepository.findByUsernameIgnoreCase("dilliprasath1225"))
                .thenReturn(Optional.of(sampleUser));

        User result = userService.getUserByEmailOrUsername("dilliprasath1225@gmail.com");

        assertNotNull(result);
        assertEquals("dilliprasath1225", result.getUsername());
    }

    @Test
    @DisplayName("5. getUserByEmailOrUsername throws MailException when user is not found")
    void testGetUserByEmailOrUsername_NotFound_ThrowsMailException() {
        when(userRepository.findByIdentifierIgnoreCase("unregistered@gmail.com"))
                .thenReturn(Collections.emptyList());
        when(userRepository.findByMailAccountEmailIgnoreCase("unregistered@gmail.com"))
                .thenReturn(Optional.empty());
        when(mailAccountRepository.findByEmail("unregistered@gmail.com"))
                .thenReturn(Optional.empty());
        when(userRepository.findByUsernameIgnoreCase("unregistered"))
                .thenReturn(Optional.empty());

        MailException ex = assertThrows(MailException.class,
                () -> userService.getUserByEmailOrUsername("unregistered@gmail.com"));

        assertTrue(ex.getMessage().contains("User not found: unregistered@gmail.com"));
    }

    @Test
    @DisplayName("6. getUserByEmailOrUsername throws MailException when identifier is blank")
    void testGetUserByEmailOrUsername_BlankIdentifier_ThrowsMailException() {
        assertThrows(MailException.class, () -> userService.getUserByEmailOrUsername("   "));
        assertThrows(MailException.class, () -> userService.getUserByEmailOrUsername(null));
    }
}
