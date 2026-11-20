package com.btctech.mailapp.service;

import com.btctech.mailapp.entity.MailAccount;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.repository.ProcessedEmailRepository;
import com.btctech.mailapp.repository.UserRepository;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Date;
import java.util.Properties;
import java.util.UUID;

/**
 * Service responsible for automatically delivering a professionally designed,
 * Zoho Mail-inspired Welcome Email to newly registered BNX Mail users.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WelcomeEmailService {

    private final MailReceiveService mailReceiveService;
    private final UserRepository userRepository;
    private final ProcessedEmailRepository processedEmailRepository;

    @Value("${mail.smtp.host:localhost}")
    private String smtpHost;

    @Value("${mail.smtp.port:25}")
    private int smtpPort;

    @Value("${spring.mail.from:${SPRING_MAIL_FROM:${mail.from:${MAIL_FROM:${spring.mail.username:${SPRING_MAIL_USERNAME:noreply@bnxmail.com}}}}}}")
    private String mailFrom;

    @Value("${mail.domain:bnxmail.com}")
    private String defaultDomain;

    /**
     * Sends the welcome email to the newly created mailbox with strict duplicate prevention.
     */
    public boolean sendWelcomeEmail(User user, MailAccount mailAccount, String plainPassword) {
        if (user == null || mailAccount == null) {
            log.warn("Cannot send welcome email: user or mailAccount is null");
            return false;
        }

        // Duplicate prevention: send only once per newly registered account
        if (Boolean.TRUE.equals(user.getWelcomeEmailSent())) {
            log.info("Welcome email already sent for user: {} (id: {})", user.getUsername(), user.getId());
            return false;
        }

        String recipientEmail = mailAccount.getEmail();
        if (recipientEmail == null || recipientEmail.trim().isEmpty()) {
            log.warn("Cannot send welcome email: recipient email address is missing");
            return false;
        }

        log.info("Generating and sending welcome email to new user: {} <{}>", user.getUsername(), recipientEmail);

        try {
            // Determine active domain
            String activeDomain = defaultDomain;
            if (recipientEmail.contains("@")) {
                activeDomain = recipientEmail.substring(recipientEmail.indexOf("@") + 1);
            }

            // Determine sender address
            String fromAddress = (mailFrom != null && !mailFrom.trim().isEmpty())
                    ? mailFrom.trim()
                    : "welcome@" + activeDomain;

            // Resolve personal greeting name
            String userName = resolveUserName(user);

            // Construct MimeMessage
            Properties props = new Properties();
            props.put("mail.smtp.host", smtpHost);
            props.put("mail.smtp.port", String.valueOf(smtpPort));

            Session session = Session.getInstance(props);
            MimeMessage message = new MimeMessage(session);

            message.setFrom(new InternetAddress(fromAddress, "BNX Mail", "UTF-8"));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(recipientEmail));
            message.setSubject("Welcome to BNX Mail!", "UTF-8");
            message.setSentDate(new Date());

            String messageId = "<welcome-" + UUID.randomUUID() + "@" + activeDomain + ">";
            message.setHeader("Message-ID", messageId);
            message.setHeader("X-Mailer", "BNX-Mail-System");

            // Build alternative multipart (plain text + rich HTML)
            MimeMultipart multipart = new MimeMultipart("alternative");

            // 1. Plain Text Version
            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(buildPlainText(userName, recipientEmail), "UTF-8");
            multipart.addBodyPart(textPart);

            // 2. Zoho Mail-inspired Rich HTML Version
            MimeBodyPart htmlPart = new MimeBodyPart();
            htmlPart.setContent(buildHtmlTemplate(userName, recipientEmail), "text/html; charset=UTF-8");
            multipart.addBodyPart(htmlPart);

            message.setContent(multipart);
            message.saveChanges();

            boolean delivered = false;

            // Delivery Strategy 1: IMAP Append to INBOX
            if (plainPassword != null && !plainPassword.trim().isEmpty()) {
                delivered = deliverViaImap(recipientEmail, plainPassword, message);
            }

            // Delivery Strategy 2: Direct Maildir delivery (fallback for offline IMAP or testing)
            if (!delivered && mailAccount.getMaildirPath() != null) {
                delivered = deliverViaMaildir(mailAccount.getMaildirPath(), activeDomain, message);
            }

            // Delivery Strategy 3: Local SMTP transport
            if (!delivered) {
                delivered = deliverViaSmtp(session, message);
            }

            // Mark user as having received the welcome email
            user.setWelcomeEmailSent(true);
            userRepository.save(user);

            log.info("✓ Welcome email successfully delivered to {} (delivered={})", recipientEmail, delivered);
            return true;

        } catch (Exception e) {
            log.error("Failed to generate/deliver welcome email to {}: {}", recipientEmail, e.getMessage(), e);
            // Handle gracefully so registration is not disrupted
            return false;
        }
    }

    /**
     * Resolves human-friendly display name for greeting.
     */
    private String resolveUserName(User user) {
        if (user.getFirstName() != null && !user.getFirstName().trim().isEmpty()) {
            return user.getFirstName().trim();
        }
        if (user.getDisplayName() != null && !user.getDisplayName().trim().isEmpty()) {
            return user.getDisplayName().trim();
        }
        if (user.getUsername() != null && !user.getUsername().trim().isEmpty()) {
            return user.getUsername().trim();
        }
        return "there";
    }

    /**
     * Delivery Strategy 1: Appends the message directly to INBOX via IMAP.
     */
    private boolean deliverViaImap(String email, String password, MimeMessage message) {
        Store store = null;
        Folder inbox = null;
        try {
            store = mailReceiveService.connect(email, password);
            inbox = store.getFolder("INBOX");
            if (!inbox.exists()) {
                inbox.create(Folder.HOLDS_MESSAGES);
            }
            inbox.open(Folder.READ_WRITE);

            MimeMessage copy = new MimeMessage(message);
            copy.setFlag(Flags.Flag.SEEN, false); // Mark as unread for newly arrived mail
            copy.saveChanges();

            inbox.appendMessages(new Message[]{copy});
            log.info("✓ Welcome email appended to INBOX via IMAP for {}", email);
            return true;
        } catch (Exception e) {
            log.warn("IMAP append delivery failed for {}: {}", email, e.getMessage());
            return false;
        } finally {
            if (inbox != null && inbox.isOpen()) {
                try { inbox.close(false); } catch (Exception ignored) {}
            }
            if (store != null && store.isConnected()) {
                try { store.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Delivery Strategy 2: Direct file injection into Maildir/new directory.
     * Guarantees Dovecot picks up the email immediately without network transport.
     */
    private boolean deliverViaMaildir(String maildirPath, String activeDomain, MimeMessage message) {
        try {
            File base = new File(maildirPath);
            File maildirNew;
            if (base.getName().equalsIgnoreCase("Maildir") || new File(base, "new").exists()) {
                maildirNew = new File(base, "new");
            } else {
                maildirNew = new File(base, "Maildir/new");
            }
            if (!maildirNew.exists()) {
                maildirNew.mkdirs();
            }

            String fileName = System.currentTimeMillis() + "." + UUID.randomUUID().toString().replace("-", "") + "." + activeDomain;
            File targetFile = new File(maildirNew, fileName);

            try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                message.writeTo(fos);
            }

            targetFile.setReadable(true, false);
            targetFile.setWritable(true, false);

            log.info("✓ Welcome email written directly to Maildir/new at {}", targetFile.getAbsolutePath());
            return true;
        } catch (Exception e) {
            log.warn("Direct Maildir delivery failed at {}: {}", maildirPath, e.getMessage());
            return false;
        }
    }

    /**
     * Delivery Strategy 3: Local SMTP transport.
     */
    private boolean deliverViaSmtp(Session session, MimeMessage message) {
        Transport transport = null;
        try {
            transport = session.getTransport("smtp");
            transport.connect();
            transport.sendMessage(message, message.getAllRecipients());
            log.info("✓ Welcome email sent via local SMTP transport");
            return true;
        } catch (Exception e) {
            log.warn("Local SMTP transport delivery failed: {}", e.getMessage());
            return false;
        } finally {
            if (transport != null) {
                try { transport.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Builds plain text fallback for the welcome email.
     */
    private String buildPlainText(String userName, String userEmail) {
        return "Welcome to BNX Mail!\n\n" +
                "Welcome, " + userName + "!\n\n" +
                "Thank you for joining BNX Mail. Your mailbox (" + userEmail + ") is now ready to use.\n\n" +
                "With BNX Mail, stay connected, organized, and productive. We're excited to have you with us!\n\n" +
                "Get Started: https://www.bnxmail.com/inbox\n\n" +
                "Happy Mailing!\n" +
                "The BNX Mail Team\n";
    }

    /**
     * Builds the Zoho Mail-inspired responsive HTML welcome email.
     */
    private String buildHtmlTemplate(String userName, String userEmail) {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"en\">\n" +
                "<head>\n" +
                "  <meta charset=\"UTF-8\">\n" +
                "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "  <title>Welcome to BNX Mail!</title>\n" +
                "  <style>\n" +
                "    body { margin: 0; padding: 0; background-color: #f8fafc; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; }\n" +
                "    .container { max-width: 600px; margin: 30px auto; background-color: #ffffff; border-radius: 14px; border: 1px solid #e2e8f0; overflow: hidden; box-shadow: 0 4px 20px rgba(0,0,0,0.05); }\n" +
                "    .header { padding: 32px 40px 16px 40px; text-align: center; border-bottom: 1px solid #f1f5f9; }\n" +
                "    .content { padding: 36px 44px; text-align: center; }\n" +
                "    .btn { display: inline-block; background-color: #135bec; color: #ffffff !important; text-decoration: none; font-size: 15px; font-weight: 600; padding: 13px 36px; border-radius: 8px; box-shadow: 0 2px 8px rgba(19, 91, 236, 0.3); transition: background-color 0.2s ease; }\n" +
                "    .footer { padding: 28px 40px; background-color: #f8fafc; text-align: center; border-top: 1px solid #f1f5f9; font-size: 13px; color: #64748b; }\n" +
                "    @media only screen and (max-width: 620px) {\n" +
                "      .container { margin: 10px; border-radius: 10px; }\n" +
                "      .content { padding: 28px 20px; }\n" +
                "      .header { padding: 24px 20px 12px 20px; }\n" +
                "    }\n" +
                "  </style>\n" +
                "</head>\n" +
                "<body style=\"margin: 0; padding: 20px 10px; background-color: #f8fafc; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;\">\n" +
                "  <table width=\"100%\" border=\"0\" cellspacing=\"0\" cellpadding=\"0\" style=\"background-color: #f8fafc;\">\n" +
                "    <tr>\n" +
                "      <td align=\"center\">\n" +
                "        <table class=\"container\" width=\"100%\" border=\"0\" cellspacing=\"0\" cellpadding=\"0\" style=\"max-width: 600px; background-color: #ffffff; border-radius: 14px; border: 1px solid #e2e8f0; overflow: hidden; box-shadow: 0 4px 20px rgba(0,0,0,0.05);\">\n" +
                "          <!-- TOP ACCENT BAR -->\n" +
                "          <tr>\n" +
                "            <td height=\"4\" style=\"background: linear-gradient(90deg, #135bec 0%, #3b82f6 50%, #60a5fa 100%); line-height: 4px; font-size: 4px;\">&nbsp;</td>\n" +
                "          </tr>\n" +
                "          <!-- LOGO HEADER -->\n" +
                "          <tr>\n" +
                "            <td class=\"header\" style=\"padding: 32px 40px 20px 40px; text-align: center; border-bottom: 1px solid #f1f5f9;\">\n" +
                "              <table align=\"center\" border=\"0\" cellspacing=\"0\" cellpadding=\"0\">\n" +
                "                <tr>\n" +
                "                  <td valign=\"middle\" style=\"padding-right: 10px;\">\n" +
                "                    <svg width=\"34\" height=\"34\" viewBox=\"0 0 36 36\" fill=\"none\" xmlns=\"http://www.w3.org/2000/svg\">\n" +
                "                      <rect width=\"36\" height=\"36\" rx=\"8\" fill=\"#135bec\"/>\n" +
                "                      <path d=\"M8 12.5L18 20L28 12.5M8 12.5C8 11.6716 8.67157 11 9.5 11H26.5C27.3284 11 28 11.6716 28 12.5M8 12.5V23.5C8 24.3284 8.67157 25 9.5 25H26.5C27.3284 25 28 24.3284 28 23.5V12.5\" stroke=\"#ffffff\" stroke-width=\"2.2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>\n" +
                "                    </svg>\n" +
                "                  </td>\n" +
                "                  <td valign=\"middle\">\n" +
                "                    <span style=\"font-size: 24px; font-weight: 800; color: #135bec; letter-spacing: -0.5px;\">BNX<span style=\"font-weight: 500; color: #1e293b;\">mail</span></span>\n" +
                "                  </td>\n" +
                "                </tr>\n" +
                "              </table>\n" +
                "            </td>\n" +
                "          </tr>\n" +
                "          <!-- BODY CONTENT -->\n" +
                "          <tr>\n" +
                "            <td class=\"content\" style=\"padding: 36px 44px; text-align: center;\">\n" +
                "              <!-- ENVELOPE ILLUSTRATION -->\n" +
                "              <table align=\"center\" border=\"0\" cellspacing=\"0\" cellpadding=\"0\" style=\"margin: 0 auto 24px auto;\">\n" +
                "                <tr>\n" +
                "                  <td align=\"center\">\n" +
                "                    <svg width=\"120\" height=\"100\" viewBox=\"0 0 140 120\" fill=\"none\" xmlns=\"http://www.w3.org/2000/svg\">\n" +
                "                      <ellipse cx=\"70\" cy=\"105\" rx=\"55\" ry=\"8\" fill=\"#e2e8f0\" opacity=\"0.7\"/>\n" +
                "                      <rect x=\"25\" y=\"45\" width=\"90\" height=\"60\" rx=\"6\" fill=\"#1e40af\"/>\n" +
                "                      <rect x=\"35\" y=\"20\" width=\"70\" height=\"55\" rx=\"4\" fill=\"#ffffff\" stroke=\"#e2e8f0\" stroke-width=\"1.5\"/>\n" +
                "                      <line x1=\"45\" y1=\"32\" x2=\"75\" y2=\"32\" stroke=\"#135bec\" stroke-width=\"3\" stroke-linecap=\"round\"/>\n" +
                "                      <line x1=\"45\" y1=\"42\" x2=\"85\" y2=\"42\" stroke=\"#cbd5e1\" stroke-width=\"2.5\" stroke-linecap=\"round\"/>\n" +
                "                      <line x1=\"45\" y1=\"51\" x2=\"70\" y2=\"51\" stroke=\"#cbd5e1\" stroke-width=\"2.5\" stroke-linecap=\"round\"/>\n" +
                "                      <path d=\"M25 45L70 78L115 45\" stroke=\"#3b82f6\" stroke-width=\"2.5\" stroke-linejoin=\"round\"/>\n" +
                "                      <path d=\"M25 105L58 75M115 105L82 75\" stroke=\"#1d4ed8\" stroke-width=\"2\"/>\n" +
                "                      <circle cx=\"118\" cy=\"28\" r=\"4\" fill=\"#fbbf24\"/>\n" +
                "                      <path d=\"M118 20V23M118 33V36M110 28H113M123 28H126\" stroke=\"#fbbf24\" stroke-width=\"1.5\" stroke-linecap=\"round\"/>\n" +
                "                      <circle cx=\"22\" cy=\"35\" r=\"2.5\" fill=\"#60a5fa\"/>\n" +
                "                    </svg>\n" +
                "                  </td>\n" +
                "                </tr>\n" +
                "              </table>\n" +
                "              <!-- HEADINGS -->\n" +
                "              <h1 style=\"margin: 0 0 10px 0; font-size: 26px; font-weight: 700; color: #0f172a; line-height: 1.3;\">\n" +
                "                Welcome to BNX Mail!\n" +
                "              </h1>\n" +
                "              <p style=\"margin: 0 0 20px 0; font-size: 17px; font-weight: 600; color: #135bec;\">\n" +
                "                Welcome, " + escapeHtml(userName) + "!\n" +
                "              </p>\n" +
                "              <!-- WELCOME MESSAGE -->\n" +
                "              <p style=\"margin: 0 0 16px 0; font-size: 15px; line-height: 1.65; color: #475569;\">\n" +
                "                Thank you for joining BNX Mail. Your mailbox is now ready to use.\n" +
                "              </p>\n" +
                "              <p style=\"margin: 0 0 32px 0; font-size: 15px; line-height: 1.65; color: #475569;\">\n" +
                "                With BNX Mail, stay connected, organized, and productive. We're excited to have you with us!\n" +
                "              </p>\n" +
                "              <!-- CALL TO ACTION BUTTON -->\n" +
                "              <table align=\"center\" border=\"0\" cellspacing=\"0\" cellpadding=\"0\" style=\"margin: 0 auto;\">\n" +
                "                <tr>\n" +
                "                  <td align=\"center\" style=\"border-radius: 8px; background-color: #135bec;\">\n" +
                "                    <a href=\"https://www.bnxmail.com/inbox\" target=\"_blank\" class=\"btn\" style=\"display: inline-block; background-color: #135bec; color: #ffffff !important; text-decoration: none; font-size: 15px; font-weight: 600; padding: 13px 38px; border-radius: 8px;\">\n" +
                "                      Get Started &rarr;\n" +
                "                    </a>\n" +
                "                  </td>\n" +
                "                </tr>\n" +
                "              </table>\n" +
                "            </td>\n" +
                "          </tr>\n" +
                "          <!-- FOOTER -->\n" +
                "          <tr>\n" +
                "            <td class=\"footer\" style=\"padding: 28px 40px; background-color: #f8fafc; text-align: center; border-top: 1px solid #f1f5f9; font-size: 13px; color: #64748b;\">\n" +
                "              <p style=\"margin: 0 0 4px 0; font-size: 15px; color: #334155; font-weight: 500;\">Happy Mailing!</p>\n" +
                "              <p style=\"margin: 0 0 16px 0; font-size: 15px; color: #0f172a; font-weight: 700;\">The BNX Mail Team</p>\n" +
                "              <p style=\"margin: 0; font-size: 12px; color: #94a3b8;\">&copy; 2026 BNX Mail. All rights reserved.</p>\n" +
                "            </td>\n" +
                "          </tr>\n" +
                "        </table>\n" +
                "      </td>\n" +
                "    </tr>\n" +
                "  </table>\n" +
                "</body>\n" +
                "</html>";
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
