package com.btctech.mailapp.service;

import com.btctech.mailapp.entity.MailAccount;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.repository.ProcessedEmailRepository;
import com.btctech.mailapp.repository.UserRepository;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.Properties;
import jakarta.mail.Session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WelcomeEmailServiceTest {

    private MailReceiveService mailReceiveService;
    private UserRepository userRepository;
    private ProcessedEmailRepository processedEmailRepository;
    private WelcomeEmailService welcomeEmailService;

    @BeforeEach
    void setUp() {
        mailReceiveService = mock(MailReceiveService.class);
        userRepository = mock(UserRepository.class);
        processedEmailRepository = mock(ProcessedEmailRepository.class);

        welcomeEmailService = new WelcomeEmailService(mailReceiveService, userRepository, processedEmailRepository);
        ReflectionTestUtils.setField(welcomeEmailService, "defaultDomain", "bnxmail.com");
        ReflectionTestUtils.setField(welcomeEmailService, "mailFrom", "welcome@bnxmail.com");
        ReflectionTestUtils.setField(welcomeEmailService, "smtpHost", "127.0.0.1");
        ReflectionTestUtils.setField(welcomeEmailService, "smtpPort", 25);
    }

    @Test
    @DisplayName("Should skip sending welcome email if user already received it")
    void shouldSkipIfAlreadySent() {
        User user = new User();
        user.setId(1L);
        user.setUsername("ashwin");
        user.setWelcomeEmailSent(true);

        MailAccount account = new MailAccount();
        account.setEmail("ashwin@bnxmail.com");

        boolean result = welcomeEmailService.sendWelcomeEmail(user, account, "testPass");

        assertFalse(result);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should handle null user or mailAccount gracefully")
    void shouldHandleNullInputs() {
        assertFalse(welcomeEmailService.sendWelcomeEmail(null, null, "pass"));
        assertFalse(welcomeEmailService.sendWelcomeEmail(new User(), null, "pass"));
    }

    @Test
    @DisplayName("Should deliver welcome email with Zoho-inspired styling and exact required copy")
    void shouldDeliverViaMaildirAndMarkUser(@TempDir Path tempDir) throws Exception {
        // Prepare temporary Maildir directory structure
        File maildir = tempDir.resolve("Maildir").toFile();
        File newDir = new File(maildir, "new");
        newDir.mkdirs();

        User user = new User();
        user.setId(2L);
        user.setUsername("testuser10");
        user.setFirstName("Ashwin");
        user.setWelcomeEmailSent(false);

        MailAccount account = new MailAccount();
        account.setEmail("ashwin@bnxmail.com");
        account.setMaildirPath(maildir.getAbsolutePath());

        // Deliver welcome email via Maildir
        boolean result = welcomeEmailService.sendWelcomeEmail(user, account, null);

        assertTrue(result, "Welcome email should be delivered successfully via Maildir");
        assertTrue(user.getWelcomeEmailSent(), "User welcomeEmailSent flag should be set to true");
        verify(userRepository, times(1)).save(user);

        // Verify email in Maildir/new
        File[] newFiles = newDir.listFiles();
        assertNotNull(newFiles, "Maildir/new directory should exist");
        assertEquals(1, newFiles.length, "Maildir/new should contain exactly 1 welcome email");

        // Parse and verify MIME content
        Session session = Session.getDefaultInstance(new Properties());
        MimeMessage parsedMessage;
        try (FileInputStream fis = new FileInputStream(newFiles[0])) {
            parsedMessage = new MimeMessage(session, fis);
        }

        assertEquals("Welcome to BNX Mail!", parsedMessage.getSubject());
        assertTrue(parsedMessage.getFrom()[0].toString().contains("BNX Mail"));

        String rawContent = java.nio.file.Files.readString(newFiles[0].toPath(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(rawContent.contains("Welcome, Ashwin!"), "Must contain personalized greeting");
        assertTrue(rawContent.contains("Thank you for joining BNX Mail. Your mailbox is now ready to use."), "Must contain required welcome text");
        assertTrue(rawContent.contains("With BNX Mail, stay connected, organized, and productive. We're excited to have you with us!"), "Must contain secondary message");
        assertTrue(rawContent.contains("Get Started"), "Must contain Get Started button");
        assertTrue(rawContent.contains("Happy Mailing!"), "Must contain Happy Mailing footer");
        assertTrue(rawContent.contains("The BNX Mail Team"), "Must contain The BNX Mail Team footer");
    }
}