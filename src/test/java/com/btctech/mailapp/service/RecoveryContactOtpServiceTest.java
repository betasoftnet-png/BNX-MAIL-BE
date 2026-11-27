package com.btctech.mailapp.service;

import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.entity.UserSettings;
import com.btctech.mailapp.exception.MailException;
import com.btctech.mailapp.repository.*;
import com.btctech.mailapp.config.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RecoveryContactOtpServiceTest {

    private UserService userService;
    private JavaMailSender javaMailSender;
    private AuthService authService;
    private User testUser;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        javaMailSender = mock(JavaMailSender.class);

        authService = new AuthService(
                mock(RefreshTokenRepository.class),
                mock(AppealRepository.class),
                mock(JwtUtil.class),
                mock(MailboxService.class),
                mock(SessionService.class),
                userService,
                mock(UserRepository.class),
                mock(MailAccountRepository.class),
                mock(PasswordResetTokenRepository.class),
                javaMailSender,
                mock(BusinessProfileRepository.class),
                mock(ExternalAppSessionRepository.class),
                mock(org.springframework.messaging.simp.SimpMessagingTemplate.class)
        );

        ReflectionTestUtils.setField(authService, "mailFrom", "noreply@bnxmail.com");
        ReflectionTestUtils.setField(authService, "smsAuthKey", "testAuthKey");
        ReflectionTestUtils.setField(authService, "smsTemplateId", "testTemplateId");

        testUser = new User();
        testUser.setId(101L);
        testUser.setUsername("testuser");
    }

    @Test
    @DisplayName("Send recovery email OTP successfully and verify with correct code")
    void testSendAndVerifyEmailOtp() {
        String email = "recovery@test.com";

        // 1. Send OTP
        authService.sendRecoveryContactOtp(testUser, "email", email);

        // Verify email was sent via JavaMailSender
        ArgumentCaptor<SimpleMailMessage> msgCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender, times(1)).send(msgCaptor.capture());
        SimpleMailMessage sentMsg = msgCaptor.getValue();
        assertEquals(email, sentMsg.getTo()[0]);
        assertTrue(sentMsg.getText().contains("Your verification code for BNX Mail Recovery Email is:"));

        // Extract the generated 6-digit OTP from message
        String text = sentMsg.getText();
        String codePrefix = "is: ";
        int idx = text.indexOf(codePrefix);
        String otp = text.substring(idx + codePrefix.length(), idx + codePrefix.length() + 6);
        assertEquals(6, otp.length());

        // 2. Verify with correct OTP
        authService.verifyRecoveryContactOtp(testUser, "email", email, otp);

        // Verify user service was called to mark recovery email verified
        verify(userService, times(1)).markRecoveryEmailVerified(testUser, email);
    }

    @Test
    @DisplayName("Fail verification when OTP is incorrect and track attempt count")
    void testVerifyIncorrectOtp() {
        String email = "recovery2@test.com";
        authService.sendRecoveryContactOtp(testUser, "email", email);

        // Attempt with wrong OTP
        MailException ex = assertThrows(MailException.class, () ->
                authService.verifyRecoveryContactOtp(testUser, "email", email, "000000")
        );
        assertTrue(ex.getMessage().contains("Invalid verification code") || ex.getMessage().contains("attempts remaining"));

        verify(userService, never()).markRecoveryEmailVerified(any(), any());
    }

    @Test
    @DisplayName("Enforce 60-second cooldown rate limit on resending OTP")
    void testCooldownRateLimit() {
        String email = "cooldown@test.com";
        authService.sendRecoveryContactOtp(testUser, "email", email);

        // Immediate retry should fail with cooldown message
        MailException ex = assertThrows(MailException.class, () ->
                authService.sendRecoveryContactOtp(testUser, "email", email)
        );
        assertTrue(ex.getMessage().contains("Please wait") && ex.getMessage().contains("seconds before requesting a new code"));
    }

    @Test
    @DisplayName("Reject invalid email format")
    void testInvalidEmailFormat() {
        MailException ex = assertThrows(MailException.class, () ->
                authService.sendRecoveryContactOtp(testUser, "email", "invalid-email")
        );
        assertTrue(ex.getMessage().contains("valid recovery email"));
    }

    @Test
    @DisplayName("Reject phone number shorter than 10 digits")
    void testInvalidPhoneNumber() {
        MailException ex = assertThrows(MailException.class, () ->
                authService.sendRecoveryContactOtp(testUser, "phone", "12345")
        );
        assertTrue(ex.getMessage().contains("10 digits"));
    }

    @Test
    @DisplayName("Support target aliases (mobile, sms, etc.) and DTO resolution")
    void testTargetAliasesAndDto() {
        com.btctech.mailapp.dto.SendRecoveryOtpRequest req1 = new com.btctech.mailapp.dto.SendRecoveryOtpRequest();
        req1.setTarget("mobile");
        req1.setContact("9876543210");
        assertEquals("phone", req1.resolveTarget());

        com.btctech.mailapp.dto.SendRecoveryOtpRequest req2 = new com.btctech.mailapp.dto.SendRecoveryOtpRequest();
        req2.setContact("test@example.com");
        assertEquals("email", req2.resolveTarget());

        com.btctech.mailapp.dto.VerifyRecoveryOtpRequest req3 = new com.btctech.mailapp.dto.VerifyRecoveryOtpRequest();
        req3.setContact("919876543210");
        req3.setOtp("123456");
        assertEquals("phone", req3.resolveTarget());
        assertEquals("123456", req3.resolveOtp());
    }
}
