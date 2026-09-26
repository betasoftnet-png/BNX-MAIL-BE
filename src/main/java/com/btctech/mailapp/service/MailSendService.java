package com.btctech.mailapp.service;

import com.btctech.mailapp.dto.BulkMailRequest;
import com.btctech.mailapp.dto.SendMailRequest;
import com.btctech.mailapp.dto.AttachmentInfo;
import com.btctech.mailapp.exception.MailException;
import org.springframework.scheduling.annotation.Async;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMultipart;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MailSendService {
    
    private final MailReceiveService mailReceiveService;

    @Value("${mail.smtp.host:localhost}")
    private String smtpHost;

    @Value("${mail.smtp.port:25}")
    private int smtpPort;

    @Value("${mail.imap.host:localhost}")
    private String imapHost;

    @Value("${mail.imap.port:143}")
    private int imapPort;


    public void sendMail(String fromEmail, String password, SendMailRequest request) {
        
        log.info("Attempting to send email from {} to {}", fromEmail, request.getTo());
        
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", smtpHost);
            props.put("mail.smtp.localhost", "mail.bnxmail.com");
            props.put("mail.smtp.port", String.valueOf(smtpPort));
            props.put("mail.smtp.auth", "false"); 
            props.put("mail.smtp.starttls.enable", "false"); 
            props.put("mail.smtp.ssl.trust", "*");
            props.put("mail.smtp.timeout", "10000"); 
            props.put("mail.smtp.connectiontimeout", "10000");
            
            log.debug("SMTP Config: host={}, port={}", smtpHost, smtpPort);
            
            Session session = Session.getInstance(props);
            MimeMessage message = new MimeMessage(session);
            // message.setHeader(
            //     "Message-ID",
            //     "<" + java.util.UUID.randomUUID() + "@mail.bnxmail.com>"
            // );
            
            InternetAddress fromAddress;
            if (request.getFromName() != null && !request.getFromName().isEmpty()) {
                fromAddress = new InternetAddress(fromEmail, request.getFromName(), "UTF-8");
            } else {
                fromAddress = new InternetAddress(fromEmail);
            }
            message.setFrom(fromAddress);
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(request.getTo()));
            
            if (request.getCc() != null && !request.getCc().isEmpty()) {
                message.setRecipients(Message.RecipientType.CC, InternetAddress.parse(request.getCc()));
            }
            
            if (request.getBcc() != null && !request.getBcc().isEmpty()) {
                message.setRecipients(Message.RecipientType.BCC, InternetAddress.parse(request.getBcc()));
            }
            
            message.setSubject(request.getSubject());
            
            if (request.getAttachments() != null && !request.getAttachments().isEmpty()) {
                Multipart multipart = new MimeMultipart();
                MimeBodyPart messageBodyPart = new MimeBodyPart();
                if (request.getIsHtml() != null && request.getIsHtml()) {
                    messageBodyPart.setContent(request.getBody(), "text/html; charset=utf-8");
                } else {
                    messageBodyPart.setText(request.getBody(), "utf-8");
                }
                multipart.addBodyPart(messageBodyPart);

  
                for (AttachmentInfo attachment : request.getAttachments()) {
                    MimeBodyPart attachPart = new MimeBodyPart();
                    try {
                        File f = new File(attachment.getFilePath());
                        if (f.exists()) {
                            attachPart.attachFile(f);
                        } else {
                            log.warn("Attachment file not found on disk at: {}", attachment.getFilePath());
                        }
                        attachPart.setFileName(attachment.getFileName());
                        attachPart.setDisposition(Part.ATTACHMENT);
                        multipart.addBodyPart(attachPart);
                    } catch (IOException ex) {
                        log.error("Failed to attach file: {}", attachment.getFileName(), ex);
                    }
                }

                message.setContent(multipart);
            } else {
                // if (request.getIsHtml() != null && request.getIsHtml()) {
                //     message.setContent(request.getBody(), "text/html; charset=utf-8");
                // } else {
                //     message.setText(request.getBody(), "utf-8");
                // }
                if (request.getIsHtml() != null && request.getIsHtml()) {
                    MimeMultipart alternative = new MimeMultipart("alternative");

                    MimeBodyPart textPart = new MimeBodyPart();
                    textPart.setText(request.getBody(), "utf-8");

                    MimeBodyPart htmlPart = new MimeBodyPart();
                    htmlPart.setContent(request.getBody(), "text/html; charset=utf-8");

                    alternative.addBodyPart(textPart);
                    alternative.addBodyPart(htmlPart);

                    message.setContent(alternative);
                } else {
                    message.setText(request.getBody(), "utf-8");
                }
            }
            
            message.setSentDate(new java.util.Date());
            
            log.info("Sending email to SMTP server...");
            message.saveChanges();
            String messageId = "<" + UUID.randomUUID() + "@mail.bnxmail.com>";
            message.setHeader("Message-ID", messageId);

            log.info("Message-ID generated: {}", message.getHeader("Message-ID", null));
            // Transport.send(message);
            Transport transport = session.getTransport("smtp");
            transport.connect();
            transport.sendMessage(message, message.getAllRecipients());
            transport.close();
            
            log.info("✓ Email sent successfully from {} to {}", fromEmail, request.getTo());
            
          
            if (password != null && !password.isEmpty()) {
                saveCopyToSent(fromEmail, password, message);
            } else {
                log.info("Skipping Sent folder IMAP archival (no password provided / public send)");
            }
            
        } catch (MessagingException e) {
            log.error("Failed to send email from {} to {}: {}", fromEmail, request.getTo(), e.getMessage());
            throw new MailException("Failed to send email: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error sending email: {}", e.getMessage());
            throw new MailException("Unexpected error: " + e.getMessage());
        }
    }


    @Async
    public void sendBulkMail(String fromEmail, String password, BulkMailRequest request) {
        log.info("Starting asynchronous bulk email send from {} to {} recipients", fromEmail, request.getRecipients().size());
        
        if (password == null || password.isEmpty()) {
            log.error("Bulk send failed: Password not found in session for {}", fromEmail);
            return;
        }

        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", smtpHost);
            props.put("mail.smtp.localhost", "mail.bnxmail.com");
            props.put("mail.smtp.port", String.valueOf(smtpPort));
            props.put("mail.smtp.auth", "false");
            props.put("mail.smtp.starttls.enable", "false");
            props.put("mail.smtp.timeout", "10000");

            Session session = Session.getInstance(props);

            for (String recipient : request.getRecipients()) {
                try {
                    MimeMessage message = new MimeMessage(session);
                    // message.setHeader(
                    //     "Message-ID",
                    //     "<" + java.util.UUID.randomUUID() + "@mail.bnxmail.com>"
                    // );
                    message.setFrom(new InternetAddress(fromEmail));
                    message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient));
                    message.setSubject(request.getSubject());

                    if (request.getIsHtml() != null && request.getIsHtml()) {
                        message.setContent(request.getBody(), "text/html; charset=utf-8");
                    } else {
                        message.setText(request.getBody(), "utf-8");
                    }

                message.saveChanges();
                String messageId =
                    "<" + UUID.randomUUID() + "@mail.bnxmail.com>";

                    message.setHeader("Message-ID", messageId);

                    log.info(
                        "Message-ID generated: {}",
                        message.getHeader("Message-ID", null)
                    );
                    // Transport.send(message);
                    Transport transport = session.getTransport("smtp");
                    transport.connect();
                    transport.sendMessage(message, message.getAllRecipients());
                    transport.close();
                    log.info("✓ Bulk item sent to {}", recipient);

                
                    Thread.sleep(500);
                } catch (Exception e) {
                    log.error("Failed to send bulk email to {}: {}", recipient, e.getMessage());
                }
            }
            log.info("✓ Finished bulk email send process for {}", fromEmail);
        } catch (Exception e) {
            log.error("Critical error in bulk send for {}: {}", fromEmail, e.getMessage());
        }
    }

    private void saveCopyToSent(String email, String password, MimeMessage message) {
        log.info("Starting archival process for sent email from {}", email);
        Store store = null;
        Folder sentFolder = null;

        try {
            store = mailReceiveService.connect(email, password);
            log.info("✓ Connected to IMAP for archival on {}:{}", imapHost, imapPort);
            String actualSentFolder = mailReceiveService.resolveSentFolderName(store);
            log.info("Resolved Sent folder name: {}", actualSentFolder);

            sentFolder = store.getFolder(actualSentFolder);
            if (!sentFolder.exists()) {
                log.info("Sent folder '{}' does not exist, attempting to create it...", actualSentFolder);
                sentFolder.create(Folder.HOLDS_MESSAGES);
            }

            sentFolder.open(Folder.READ_WRITE);
            
            MimeMessage copy = new MimeMessage(message);
            copy.setFlag(Flags.Flag.SEEN, true);
            copy.saveChanges();
            
            sentFolder.appendMessages(new Message[]{copy});
            log.info("✓ Message successfully archived to '{}' folder for {}", actualSentFolder, email);

        } catch (Exception e) {
            log.warn("⚠ SENT ARCHIVAL WARNING: Failed to archive copy for {}. Reason: {}", email, e.getMessage());
        } finally {
            try {
                if (sentFolder != null && sentFolder.isOpen()) sentFolder.close(false);
                if (store != null) store.close();
            } catch (MessagingException e) {
                log.debug("Error closing IMAP resources: {}", e.getMessage());
            }
        }
    }
}