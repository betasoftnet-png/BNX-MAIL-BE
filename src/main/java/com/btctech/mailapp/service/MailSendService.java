package com.btctech.mailapp.service;

import com.btctech.mailapp.dto.BulkMailRequest;
import com.btctech.mailapp.dto.SendMailRequest;
import com.btctech.mailapp.dto.AttachmentInfo;
import com.btctech.mailapp.exception.MailException;
import com.btctech.mailapp.entity.StarredEmail;
import com.btctech.mailapp.repository.StarredEmailRepository;
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
import java.util.List;
import java.util.Properties;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MailSendService {
    
    private final MailReceiveService mailReceiveService;
    private final StarredEmailRepository starredEmailRepository;

    @Value("${mail.smtp.host:localhost}")
    private String smtpHost;

    @Value("${mail.smtp.port:25}")
    private int smtpPort;

    @Value("${mail.imap.host:localhost}")
    private String imapHost;

    @Value("${mail.imap.port:143}")
    private int imapPort;


    public String sendMail(String fromEmail, String password, SendMailRequest request) {
        
        log.info("Attempting to send email from {} to {}", fromEmail, request.getTo());
        
        String draftUid = (request.getDraftUid() != null && !request.getDraftUid().isEmpty())
                ? request.getDraftUid()
                : (request.getUid() != null && !request.getUid().isEmpty() ? request.getUid() : null);
        boolean isStarred = Boolean.TRUE.equals(request.getIsStarred()) || Boolean.TRUE.equals(request.getStarred());

        // If not explicitly passed, inspect local starred database mapping for the draft UID
        if (!isStarred && draftUid != null && starredEmailRepository != null) {
            List<StarredEmail> draftStars = starredEmailRepository.findByUserEmailAndUid(fromEmail, draftUid);
            if (!draftStars.isEmpty()) {
                isStarred = true;
            }
        }
        
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
            
            String sentUid = null;
            if (password != null && !password.isEmpty()) {
                sentUid = saveCopyToSent(fromEmail, password, message, isStarred, draftUid);
            } else {
                log.info("Skipping Sent folder IMAP archival (no password provided / public send)");
            }
            return sentUid;
            
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

    private String saveCopyToSent(String email, String password, MimeMessage message, boolean isStarred, String draftUid) {
        log.info("Starting archival process for sent email from {} (isStarred={})", email, isStarred);
        Store store = null;
        Folder sentFolder = null;
        String sentUid = null;

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
            if (isStarred) {
                copy.setFlag(Flags.Flag.FLAGGED, true);
            }
            copy.saveChanges();
            
            sentFolder.appendMessages(new Message[]{copy});
            log.info("✓ Message successfully archived to '{}' folder for {}", actualSentFolder, email);

            if (sentFolder instanceof UIDFolder uidFolder) {
                int totalCount = sentFolder.getMessageCount();
                if (totalCount > 0) {
                    Message lastMsg = sentFolder.getMessage(totalCount);
                    sentUid = String.valueOf(uidFolder.getUID(lastMsg));
                    log.info("✓ Retrieved sent message UID {} in {}", sentUid, actualSentFolder);
                }
            }

            // Persist transferred star status in backend database
            if (isStarred && sentUid != null && starredEmailRepository != null) {
                List<StarredEmail> existingStars = starredEmailRepository.findByUserEmailAndUid(email, sentUid);
                if (existingStars.isEmpty()) {
                    StarredEmail star = StarredEmail.builder()
                            .userEmail(email)
                            .uid(sentUid)
                            .folderName("Sent")
                            .starredAt(java.time.LocalDateTime.now())
                            .build();
                    starredEmailRepository.save(star);
                    log.info("✓ Transferred STAR status to sent email UID {} in Sent folder for {}", sentUid, email);
                }

                // Clean up old draft stars to prevent duplicates in Starred view
                if (draftUid != null && !draftUid.isEmpty()) {
                    List<StarredEmail> oldStars = starredEmailRepository.findByUserEmailAndUid(email, draftUid);
                    if (!oldStars.isEmpty()) {
                        starredEmailRepository.deleteAll(oldStars);
                        log.info("✓ Cleaned up old draft STAR mapping for UID {}", draftUid);
                    }
                }
            }

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
        return sentUid;
    }
}