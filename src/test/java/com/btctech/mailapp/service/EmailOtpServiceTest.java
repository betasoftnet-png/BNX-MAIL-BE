package com.btctech.mailapp.service;

import com.btctech.mailapp.config.JwtUtil;
import com.btctech.mailapp.config.MailConfig;
import com.btctech.mailapp.dto.RecoveryOptionsResponse;
import com.btctech.mailapp.dto.ResetPasswordRequest;
import com.btctech.mailapp.dto.SendOtpRequest;
import com.btctech.mailapp.entity.PasswordResetToken;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.exception.MailException;
import com.btctech.mailapp.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EmailOtpServiceTest {

    private AuthService authService;
    private UserService userService;
    private PasswordResetTokenRepository passwordResetTokenRepository;
    private RefreshTokenRepository refreshTokenRepository;
    private JavaMailSender javaMailSender;

    private User testUser;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        passwordResetTokenRepository = mock(PasswordResetTokenRepository.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        javaMailSender = mock(JavaMailSender.class);
        RefreshTokenRepository rtRepo = mock(RefreshTokenRepository.class);
        AppealRepository appealRepo = mock(AppealRepository.class);
        JwtUtil jwtUtil = mock(JwtUtil.class);
        MailboxService mailboxService = mock(MailboxService.class);
        SessionService sessionService = mock(SessionService.class);
        UserRepository userRepo = mock(UserRepository.class);
        MailAccountRepository mailAccountRepo = mock(MailAccountRepository.class);
        BusinessProfileRepository bizRepo = mock(BusinessProfileRepository.class);
        ExternalAppSessionRepository extAppRepo = mock(ExternalAppSessionRepository.class);
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);

        authService = new AuthService(
                rtRepo,
                appealRepo,
                jwtUtil,
                mailboxService,
                sessionService,
                userService,
                userRepo,
                mailAccountRepo,
                passwordResetTokenRepository,
                javaMailSender,
                bizRepo,
                extAppRepo,
                messagingTemplate
        );

        ReflectionTestUtils.setField(authService, "mailFrom", "noreply@bnxmail.com");
        ReflectionTestUtils.setField(authService, "smtpHost", "smtp.provider.com");

        testUser = new User();
        testUser.setId(100L);
        testUser.setUsername("testuser");
        testUser.setEmail("testuser@bnxmail.com");
        testUser.setRecoveryEmail("recovery@externaldomain.com");
        testUser.setPhoneNumber("9876543210");
        testUser.setPassword("OldEncodedPassword123");
    }

    @Test
    @DisplayName("1. sendRecoveryOtp (EMAIL) dispatches valid 6-digit OTP to recovery email")
    void testSendRecoveryOtp_Email_Success() {
        when(userService.getUserByEmailOrUsername("testuser")).thenReturn(testUser);

        SendOtpRequest request = new SendOtpRequest();
        request.setIdentifier("testuser");
        request.setMethod("EMAIL");

        authService.sendRecoveryOtp(request);

        // Verify previous tokens were deleted
        verify(passwordResetTokenRepository).deleteByUser(testUser);

        // Verify new token was saved with ~15 min expiry
        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(passwordResetTokenRepository).save(tokenCaptor.capture());
        PasswordResetToken savedToken = tokenCaptor.getValue();
        assertNotNull(savedToken.getToken());
        assertEquals(6, savedToken.getToken().length());
        assertTrue(savedToken.getToken().matches("\\d{6}"));
        assertTrue(savedToken.getExpiryDate().isAfter(LocalDateTime.now().plusMinutes(14)));

        // Verify email dispatched via JavaMailSender
        ArgumentCaptor<SimpleMailMessage> msgCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender).send(msgCaptor.capture());
        SimpleMailMessage sentMsg = msgCaptor.getValue();
        assertEquals("noreply@bnxmail.com", sentMsg.getFrom());
        assertNotNull(sentMsg.getTo());
        assertEquals("recovery@externaldomain.com", sentMsg.getTo()[0]);
        assertEquals("Your BNX Mail Password Reset OTP", sentMsg.getSubject());
        assertNotNull(sentMsg.getText());
        assertTrue(sentMsg.getText().contains(savedToken.getToken()));
    }

    @Test
    @DisplayName("2. sendRecoveryOtp delivers to Gmail address when registered email is Gmail")
    void testSendRecoveryOtp_Gmail_Success() {
        testUser.setEmail("johndoe@gmail.com");
        testUser.setRecoveryEmail(null);
        when(userService.getUserByEmailOrUsername("johndoe@gmail.com")).thenReturn(testUser);

        SendOtpRequest request = new SendOtpRequest();
        request.setIdentifier("johndoe@gmail.com");
        request.setMethod("EMAIL");

        authService.sendRecoveryOtp(request);

        ArgumentCaptor<SimpleMailMessage> msgCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender).send(msgCaptor.capture());
        SimpleMailMessage sentMsg = msgCaptor.getValue();
        assertEquals("johndoe@gmail.com", sentMsg.getTo()[0]);
    }

    @Test
    @DisplayName("3. sendRecoveryOtp delivers to Outlook address when registered email is Outlook")
    void testSendRecoveryOtp_Outlook_Success() {
        testUser.setEmail("johndoe@outlook.com");
        testUser.setRecoveryEmail(null);
        when(userService.getUserByEmailOrUsername("johndoe@outlook.com")).thenReturn(testUser);

        SendOtpRequest request = new SendOtpRequest();
        request.setIdentifier("johndoe@outlook.com");
        request.setMethod("EMAIL");

        authService.sendRecoveryOtp(request);

        ArgumentCaptor<SimpleMailMessage> msgCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender).send(msgCaptor.capture());
        SimpleMailMessage sentMsg = msgCaptor.getValue();
        assertEquals("johndoe@outlook.com", sentMsg.getTo()[0]);
    }

    @Test
    @DisplayName("4. sendRecoveryOtp prioritizes configured recoveryEmail over registered email")
    void testSendRecoveryOtp_RecoveryEmail_PriorityOverRegisteredEmail() {
        testUser.setEmail("johndoe@bnxmail.com");
        testUser.setRecoveryEmail("johndoe.personal@yahoo.com");
        when(userService.getUserByEmailOrUsername("johndoe@bnxmail.com")).thenReturn(testUser);

        SendOtpRequest request = new SendOtpRequest();
        request.setIdentifier("johndoe@bnxmail.com");
        request.setMethod("EMAIL");

        authService.sendRecoveryOtp(request);

        ArgumentCaptor<SimpleMailMessage> msgCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender).send(msgCaptor.capture());
        SimpleMailMessage sentMsg = msgCaptor.getValue();
        assertEquals("johndoe.personal@yahoo.com", sentMsg.getTo()[0]);
    }

    @Test
    @DisplayName("5. sendRecoveryOtp throws clear configuration error when SMTP host is missing or localhost")
    void testSendRecoveryOtp_MissingSmtpConfig_ThrowsConfigurationException() {
        ReflectionTestUtils.setField(authService, "smtpHost", "localhost");
        when(userService.getUserByEmailOrUsername("testuser")).thenReturn(testUser);

        SendOtpRequest request = new SendOtpRequest();
        request.setIdentifier("testuser");
        request.setMethod("EMAIL");

        MailException ex = assertThrows(MailException.class, () -> authService.sendRecoveryOtp(request));
        assertTrue(ex.getMessage().contains("SMTP configuration is missing"));
        verifyNoInteractions(javaMailSender);
    }

    @Test
    @DisplayName("6. sendRecoveryOtp (EMAIL) throws exception and propagates failure when SMTP connection fails")
    void testSendRecoveryOtp_Email_SmtpFailure_ThrowsMailException() {
        when(userService.getUserByEmailOrUsername("testuser")).thenReturn(testUser);
        doThrow(new MailSendException("Could not connect to SMTP server"))
                .when(javaMailSender).send(any(SimpleMailMessage.class));

        SendOtpRequest request = new SendOtpRequest();
        request.setIdentifier("testuser");
        request.setMethod("EMAIL");

        MailException ex = assertThrows(MailException.class, () -> authService.sendRecoveryOtp(request));
        assertTrue(ex.getMessage().contains("SMTP"));
    }

    @Test
    @DisplayName("7. sendRecoveryOtp (EMAIL) throws exception when SMTP authentication fails")
    void testSendRecoveryOtp_Email_SmtpAuthFailure_ThrowsMailException() {
        when(userService.getUserByEmailOrUsername("testuser")).thenReturn(testUser);
        doThrow(new MailAuthenticationException("Authentication failed"))
                .when(javaMailSender).send(any(SimpleMailMessage.class));

        SendOtpRequest request = new SendOtpRequest();
        request.setIdentifier("testuser");
        request.setMethod("EMAIL");

        MailException ex = assertThrows(MailException.class, () -> authService.sendRecoveryOtp(request));
        assertTrue(ex.getMessage().contains("authentication failed"));
    }

    @Test
    @DisplayName("8. sendRecoveryOtp (EMAIL) fails if user has neither recovery email nor registered email")
    void testSendRecoveryOtp_Email_NoEmail_ThrowsException() {
        testUser.setRecoveryEmail(null);
        testUser.setEmail(null);
        when(userService.getUserByEmailOrUsername("testuser")).thenReturn(testUser);

        SendOtpRequest request = new SendOtpRequest();
        request.setIdentifier("testuser");
        request.setMethod("EMAIL");

        MailException ex = assertThrows(MailException.class, () -> authService.sendRecoveryOtp(request));
        assertTrue(ex.getMessage().contains("No recovery or registered email configured"));
        verifyNoInteractions(javaMailSender);
    }

    @Test
    @DisplayName("9. verifyOtp succeeds with valid token and fails with invalid/expired token")
    void testVerifyOtp_Flow() {
        when(userService.getUserByEmailOrUsername("testuser")).thenReturn(testUser);

        PasswordResetToken validToken = PasswordResetToken.builder()
                .user(testUser)
                .token("123456")
                .expiryDate(LocalDateTime.now().plusMinutes(10))
                .build();

        when(passwordResetTokenRepository.findByUserAndToken(testUser, "123456"))
                .thenReturn(Optional.of(validToken));
        when(passwordResetTokenRepository.findByUserAndToken(testUser, "999999"))
                .thenReturn(Optional.empty());

        // Valid OTP
        assertDoesNotThrow(() -> authService.verifyOtp("testuser", "123456"));

        // Invalid OTP
        MailException ex = assertThrows(MailException.class, () -> authService.verifyOtp("testuser", "999999"));
        assertEquals("Invalid OTP", ex.getMessage());

        // Expired OTP
        PasswordResetToken expiredToken = PasswordResetToken.builder()
                .user(testUser)
                .token("654321")
                .expiryDate(LocalDateTime.now().minusMinutes(1))
                .build();
        when(passwordResetTokenRepository.findByUserAndToken(testUser, "654321"))
                .thenReturn(Optional.of(expiredToken));

        MailException expEx = assertThrows(MailException.class, () -> authService.verifyOtp("testuser", "654321"));
        assertEquals("OTP has expired", expEx.getMessage());
        verify(passwordResetTokenRepository).delete(expiredToken);
    }

    @Test
    @DisplayName("10. resetPassword successfully updates password and invalidates used OTP")
    void testResetPassword_Success() {
        when(userService.getUserByEmailOrUsername("testuser")).thenReturn(testUser);

        PasswordResetToken validToken = PasswordResetToken.builder()
                .user(testUser)
                .token("123456")
                .expiryDate(LocalDateTime.now().plusMinutes(10))
                .build();
        when(passwordResetTokenRepository.findByUserAndToken(testUser, "123456"))
                .thenReturn(Optional.of(validToken));

        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setIdentifier("testuser");
        request.setOtp("123456");
        request.setNewPassword("NewSecurePassword@2026");

        authService.resetPassword(request);

        verify(userService).updateUserPassword(testUser, "NewSecurePassword@2026");
        verify(passwordResetTokenRepository).delete(validToken);
    }

    @Test
    @DisplayName("11. getRecoveryOptions falls back to registered email when recovery email is null")
    void testGetRecoveryOptions_FallbackToRegisteredEmail() {
        testUser.setEmail("registered@gmail.com");
        testUser.setRecoveryEmail(null);
        when(userService.getUserByEmailOrUsername("registered@gmail.com")).thenReturn(testUser);

        RecoveryOptionsResponse options = authService.getRecoveryOptions("registered@gmail.com");
        assertNotNull(options.getRecoveryEmail());
        assertTrue(options.getRecoveryEmail().contains("@gmail.com"));
    }

    @Test
    @DisplayName("12. MailConfig initializes JavaMailSender correctly with external properties")
    void testMailConfig_Properties() {
        MailConfig mailConfig = new MailConfig();
        ReflectionTestUtils.setField(mailConfig, "host", "smtp.hostinger.com");
        ReflectionTestUtils.setField(mailConfig, "port", 465);
        ReflectionTestUtils.setField(mailConfig, "username", "noreply@bnxmail.com");
        ReflectionTestUtils.setField(mailConfig, "password", "SecretPass123");
        ReflectionTestUtils.setField(mailConfig, "auth", true);
        ReflectionTestUtils.setField(mailConfig, "starttls", false);
        ReflectionTestUtils.setField(mailConfig, "starttlsRequired", false);
        ReflectionTestUtils.setField(mailConfig, "ssl", true);
        ReflectionTestUtils.setField(mailConfig, "sslTrust", "*");
        ReflectionTestUtils.setField(mailConfig, "protocol", "smtp");

        JavaMailSender sender = mailConfig.javaMailSender();
        assertTrue(sender instanceof JavaMailSenderImpl);
        JavaMailSenderImpl impl = (JavaMailSenderImpl) sender;

        assertEquals("smtp.hostinger.com", impl.getHost());
        assertEquals(465, impl.getPort());
        assertEquals("noreply@bnxmail.com", impl.getUsername());
        assertEquals("SecretPass123", impl.getPassword());

        Properties props = impl.getJavaMailProperties();
        assertEquals("true", props.getProperty("mail.smtp.auth"));
        assertEquals("true", props.getProperty("mail.smtp.ssl.enable"));
        assertEquals("465", props.getProperty("mail.smtp.socketFactory.port"));
        assertEquals("javax.net.ssl.SSLSocketFactory", props.getProperty("mail.smtp.socketFactory.class"));
        assertEquals("*", props.getProperty("mail.smtp.ssl.trust"));
    }

    @Test
    @DisplayName("13. getRecoveryOptions returns 200 payload with masked Gmail for registered Gmail user")
    void testGetRecoveryOptions_RegisteredGmail_Success() {
        User gmailUser = new User();
        gmailUser.setId(200L);
        gmailUser.setUsername("dilliprasath1225");
        gmailUser.setEmail("dilliprasath1225@gmail.com");
        gmailUser.setRecoveryEmail("dilliprasath1225@gmail.com");
        gmailUser.setPhoneNumber("9876543210");

        when(userService.getUserByEmailOrUsername("dilliprasath1225@gmail.com")).thenReturn(gmailUser);

        RecoveryOptionsResponse options = authService.getRecoveryOptions("dilliprasath1225@gmail.com");

        assertNotNull(options);
        assertNotNull(options.getRecoveryEmail());
        assertTrue(options.getRecoveryEmail().endsWith("@gmail.com"));
        assertEquals("d***5@gmail.com", options.getRecoveryEmail());
        assertEquals("*******210", options.getPhoneNumber());
    }

    @Test
    @DisplayName("14. sendRecoveryOtp delivers OTP specifically to registered Gmail address entered by user")
    void testSendRecoveryOtp_RegisteredGmail_DirectDelivery() {
        User gmailUser = new User();
        gmailUser.setId(200L);
        gmailUser.setUsername("dilliprasath1225");
        gmailUser.setEmail("dilliprasath1225@gmail.com");
        gmailUser.setPhoneNumber("9876543210");

        when(userService.getUserByEmailOrUsername("dilliprasath1225@gmail.com")).thenReturn(gmailUser);

        SendOtpRequest request = new SendOtpRequest();
        request.setIdentifier("dilliprasath1225@gmail.com");
        request.setMethod("EMAIL");

        authService.sendRecoveryOtp(request);

        ArgumentCaptor<SimpleMailMessage> msgCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender).send(msgCaptor.capture());
        SimpleMailMessage sentMsg = msgCaptor.getValue();
        assertEquals("dilliprasath1225@gmail.com", sentMsg.getTo()[0]);
    }

    @Test
    @DisplayName("15. sendRecoveryOtp handles identifier with surrounding whitespace correctly")
    void testSendRecoveryOtp_WhitespaceIdentifier_HandledCleanly() {
        User gmailUser = new User();
        gmailUser.setId(200L);
        gmailUser.setUsername("dilliprasath1225");
        gmailUser.setEmail("dilliprasath1225@gmail.com");

        when(userService.getUserByEmailOrUsername("dilliprasath1225@gmail.com")).thenReturn(gmailUser);

        SendOtpRequest request = new SendOtpRequest();
        request.setIdentifier("   dilliprasath1225@gmail.com   ");
        request.setMethod("EMAIL");

        authService.sendRecoveryOtp(request);

        ArgumentCaptor<SimpleMailMessage> msgCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender).send(msgCaptor.capture());
        SimpleMailMessage sentMsg = msgCaptor.getValue();
        assertEquals("dilliprasath1225@gmail.com", sentMsg.getTo()[0]);
    }

    @Test
    @DisplayName("16. getRecoveryOptions throws MailException (HTTP 400) when identifier is blank")
    void testGetRecoveryOptions_BlankIdentifier_ThrowsMailException() {
        assertThrows(MailException.class, () -> authService.getRecoveryOptions("   "));
        assertThrows(MailException.class, () -> authService.getRecoveryOptions(null));
    }
}
