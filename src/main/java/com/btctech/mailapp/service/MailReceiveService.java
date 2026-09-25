package com.btctech.mailapp.service;

import com.btctech.mailapp.entity.StarredEmail;
import com.btctech.mailapp.entity.SnoozedEmail;
import com.btctech.mailapp.repository.StarredEmailRepository;
import com.btctech.mailapp.repository.SnoozedEmailRepository;
import com.btctech.mailapp.repository.MailLabelMappingRepository;
import com.btctech.mailapp.entity.MailLabelMapping;


import com.btctech.mailapp.entity.BlockedContact;
import com.btctech.mailapp.repository.BlockedContactRepository;
import com.btctech.mailapp.entity.ProcessedEmail;
import com.btctech.mailapp.repository.ProcessedEmailRepository;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;


import com.btctech.mailapp.dto.EmailDTO;
import com.btctech.mailapp.dto.FolderResult;
import com.btctech.mailapp.dto.StorageQuotaDTO;
import com.btctech.mailapp.exception.MailException;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import com.btctech.mailapp.dto.SendMailRequest;
import org.springframework.transaction.annotation.Transactional;
import jakarta.mail.internet.MimeMultipart;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

@Slf4j
@Service
@RequiredArgsConstructor
public class MailReceiveService {

    @Value("${mail.imap.host}")
    private String imapHost;

    @Value("${mail.imap.port}")
    private int imapPort;

    @Value("${mail.imap.protocol:imap}")
    private String imapProtocol;

    @Value("${mail.imap.ssl.enable:false}")
    private boolean imapSslEnable;

    private final StarredEmailRepository starredEmailRepository;
    private final SnoozedEmailRepository snoozedEmailRepository;
    private final MailLabelMappingRepository labelMappingRepository;
    private final BlockedContactRepository blockedContactRepository;
    private final ProcessedEmailRepository processedEmailRepository;


    /**
     * Common method to fetch emails from a specific folder
     */
    private FolderResult getEmailsFromFolder(String email, String password, String folderName, int page, int limit) {
        log.info("Fetching messages from folder '{}' for: {}", folderName, email);

        Store store = null;
        Folder folder = null;

        try {
            store = connect(email, password);

            // Ensure any pending unsubscribed sender emails in INBOX are safely and idempotently moved to Spam
            if ("INBOX".equalsIgnoreCase(folderName) || "Spam".equalsIgnoreCase(folderName)) {
                processUnsubscribedEmails(store, email);
            }

            String actualFolderName = folderName;
            if ("Sent".equalsIgnoreCase(folderName)) {
                actualFolderName = resolveSentFolderName(store);
            } else if ("Trash".equalsIgnoreCase(folderName)) {
                actualFolderName = resolveTrashFolderName(store);
            } else if ("Spam".equalsIgnoreCase(folderName)) {
                actualFolderName = resolveSpamFolderName(store);
            } else if ("Snoozed".equalsIgnoreCase(folderName)) {
                actualFolderName = resolveSnoozedFolderName(store);
            } else if ("Archive".equalsIgnoreCase(folderName)) {
                actualFolderName = resolveArchiveFolderName(store);
            } else if ("Drafts".equalsIgnoreCase(folderName) || "Draft".equalsIgnoreCase(folderName)) {
                actualFolderName = resolveDraftsFolderName(store);
            }

            folder = store.getFolder(actualFolderName);
            log.info("Attempting to open folder: '{}' (Resolved from: '{}')", actualFolderName, folderName);
            
            if (!folder.exists()) {
                log.warn("⚠ FOLDER MISSING: '{}' does not exist for user {}. Returning empty list.", actualFolderName, email);
                return new FolderResult(new ArrayList<>(), 0);
            }
            
            folder.open(Folder.READ_ONLY);
            log.info("Successfully opened '{}'. Message count: {}", actualFolderName, folder.getMessageCount());

            if (!(folder instanceof UIDFolder)) {
                log.error("Folder {} does not support UIDs, falling back to message numbers (NOT RECOMMENDED)", actualFolderName);
            }

            UIDFolder uidFolder = (folder instanceof UIDFolder) ? (UIDFolder) folder : null;

            // SPECIAL HANDLING FOR SPAM:
            // Deduplicate by Message-ID / fingerprint so duplicate copies from repeated processing
            // or previous runs NEVER produce duplicate items or inflated spam counts.
            if ("Spam".equalsIgnoreCase(folderName)) {
                int totalInFolder = folder.getMessageCount();
                if (totalInFolder == 0) return new FolderResult(new ArrayList<>(), 0);

                Message[] allSpam = folder.getMessages();
                FetchProfile fp = new FetchProfile();
                fp.add(FetchProfile.Item.ENVELOPE);
                fp.add("Message-ID");
                if (folder instanceof UIDFolder) {
                    fp.add(UIDFolder.FetchProfileItem.UID);
                }
                folder.fetch(allSpam, fp);

                Set<String> seenIds = new HashSet<>();
                List<Message> uniqueSpamMessages = new ArrayList<>();
                // Iterate newest to oldest to show latest copy of unique messages
                for (int i = allSpam.length - 1; i >= 0; i--) {
                    Message sm = allSpam[i];
                    String identifier = getMessageIdentifier(sm, email);
                    if (identifier != null && seenIds.add(identifier)) {
                        uniqueSpamMessages.add(sm);
                    }
                }

                int uniqueCount = uniqueSpamMessages.size();
                int start = (page - 1) * limit;
                int end = Math.min(start + limit, uniqueCount);

                List<EmailDTO> spamEmails = new ArrayList<>();
                if (start < uniqueCount) {
                    for (int i = start; i < end; i++) {
                        try {
                            spamEmails.add(convertToDTO(uniqueSpamMessages.get(i), uidFolder, email));
                        } catch (Exception e) {
                            log.warn("Failed to parse spam message: {}", e.getMessage());
                        }
                    }
                }
                return new FolderResult(spamEmails, uniqueCount);
            }

            int messageCount = folder.getMessageCount();

            if (messageCount == 0) return new FolderResult(new ArrayList<>(), 0);

            int endIndex = messageCount - (page - 1) * limit;
            int startIndex = Math.max(1, endIndex - limit + 1);

            if (endIndex < 1) {
                return new FolderResult(new ArrayList<>(), messageCount);
            }

            Message[] messages = folder.getMessages(startIndex, endIndex);

            Map<String, LocalDateTime> blockedMap = new HashMap<>();
            if ("INBOX".equalsIgnoreCase(folderName)) {
                blockedContactRepository.findByUserEmail(email).forEach(c ->
                    blockedMap.put(c.getBlockedEmail().toLowerCase(), c.getBlockedAt())
                );
            }

            List<EmailDTO> emails = new ArrayList<>();
            for (int i = messages.length - 1; i >= 0; i--) {
                try {
                    Message msg = messages[i];
                    String subject = msg.getSubject();
                    if (subject != null && subject.toLowerCase().contains("[colab#")) {
                        continue;
                    }
                    if ("INBOX".equalsIgnoreCase(folderName) && !blockedMap.isEmpty()) {
                        Address[] from = msg.getFrom();
                        if (from != null && from.length > 0) {
                            String cleanFrom = extractEmailAddress(from[0].toString());
                            if (cleanFrom != null) {
                                String cleanLower = cleanFrom.toLowerCase();
                                if (blockedMap.containsKey(cleanLower)) {
                                    LocalDateTime blockedAt = blockedMap.get(cleanLower);
                                    java.util.Date sentDate = msg.getSentDate();
                                    LocalDateTime msgTime = (sentDate != null) ?
                                            sentDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime() :
                                            LocalDateTime.now();
                                    if (!msgTime.isBefore(blockedAt)) {
                                        // Unsubscribed sender email: already processed or skipped from INBOX
                                        continue;
                                    }
                                }
                            }
                        }
                    }
                    emails.add(convertToDTO(msg, uidFolder, email));
                } catch (Exception e) {
                    log.warn("Failed to parse message in {}: {}", folderName, e.getMessage());
                }
            }

            return new FolderResult(emails, messageCount);

        } catch (MessagingException e) {
            log.error("Failed to fetch folder {}: {}", folderName, e.getMessage(), e);
            throw new MailException("Failed to fetch " + folderName + ": " + e.getMessage());
        } finally {
            cleanup(store, folder);
        }
    }

    public FolderResult getInbox(String email, String password, int page, int limit) {
        return getEmailsFromFolder(email, password, "INBOX", page, limit);
    }

    public FolderResult getDrafts(String email, String password, int page, int limit) {
        return getEmailsFromFolder(email, password, "Drafts", page, limit);
    }

    /**
     * Get user storage quota directly via doveadm command or IMAP quota
     */
    public StorageQuotaDTO getStorageQuota(String email, String password) {
        log.info("Fetching storage quota for user: {}", email);
        long usedBytes = 0;
        long limitBytes = 5368709120L; // 5 GB default

        try {
            // First try executing doveadm command since backend runs on the same mail server
            String[] cmd = {"doveadm", "quota", "get", "-u", email};
            Process process = Runtime.getRuntime().exec(cmd);
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
            String line;
            boolean foundStorage = false;
            while ((line = reader.readLine()) != null) {
                // Example line: User quota STORAGE   689 5242880                                                                                     0
                if (line.contains("STORAGE")) {
                    String[] parts = line.trim().split("\\s+");
                    // parts: [User, quota, STORAGE, 689, 5242880, 0]
                    for (int i = 0; i < parts.length; i++) {
                        if ("STORAGE".equalsIgnoreCase(parts[i]) && i + 2 < parts.length) {
                            usedBytes = Long.parseLong(parts[i+1]) * 1024L; // doveadm returns KB
                            
                            // If limit is not "-", parse it
                            if (!"-".equals(parts[i+2])) {
                                limitBytes = Long.parseLong(parts[i+2]) * 1024L;
                            }
                            foundStorage = true;
                            break;
                        }
                    }
                }
            }
            process.waitFor();

            if (!foundStorage) {
                // Fallback to IMAP QUOTA if doveadm fails or is not available
                Store store = null;
                try {
                    store = connect(email, password);
                    if (store instanceof com.sun.mail.imap.IMAPStore) {
                        com.sun.mail.imap.IMAPStore imapStore = (com.sun.mail.imap.IMAPStore) store;
                        Quota[] quotas = imapStore.getQuota("");
                        if (quotas != null) {
                            for (Quota q : quotas) {
                                if (q.resources != null) {
                                    for (Quota.Resource r : q.resources) {
                                        if ("STORAGE".equalsIgnoreCase(r.name)) {
                                            usedBytes = r.usage * 1024L;
                                            limitBytes = r.limit * 1024L;
                                        }
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    if (store != null) {
                        try { store.close(); } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch storage quota for {}: {}", email, e.getMessage());
        }

        double percentage = (limitBytes > 0) ? ((double) usedBytes / limitBytes) * 100.0 : 0;
        return StorageQuotaDTO.builder()
                .email(email)
                .storageUsed(usedBytes)
                .storageLimit(limitBytes)
                .storagePercentage(percentage)
                .build();
    }

    public void saveDraftToIMAP(String email, String password, SendMailRequest request) {
        log.info("Saving draft to IMAP Drafts folder for {}", email);
        Store store = null;
        Folder draftsFolder = null;

        try {
            store = connect(email, password);
            String actualDraftsFolder = resolveDraftsFolderName(store);
            draftsFolder = store.getFolder(actualDraftsFolder);
            if (!draftsFolder.exists()) {
                draftsFolder.create(Folder.HOLDS_MESSAGES);
            }

            draftsFolder.open(Folder.READ_WRITE);

            Session session = Session.getInstance(new Properties());
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(email));

            if (request.getTo() != null && !request.getTo().isEmpty()) {
                message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(request.getTo()));
            }
            if (request.getCc() != null && !request.getCc().isEmpty()) {
                message.setRecipients(Message.RecipientType.CC, InternetAddress.parse(request.getCc()));
            }
            if (request.getBcc() != null && !request.getBcc().isEmpty()) {
                message.setRecipients(Message.RecipientType.BCC, InternetAddress.parse(request.getBcc()));
            }

            message.setSubject(request.getSubject() != null ? request.getSubject() : "");

            if (request.getIsHtml() != null && request.getIsHtml()) {
                message.setContent(request.getBody(), "text/html; charset=utf-8");
            } else {
                message.setText(request.getBody() != null ? request.getBody() : "", "utf-8");
            }

            // Mark as Draft and Seen
            message.setFlag(Flags.Flag.DRAFT, true);
            message.setFlag(Flags.Flag.SEEN, true);
            message.saveChanges();

            draftsFolder.appendMessages(new Message[]{message});
            log.info("✓ Draft message successfully saved to IMAP '{}' folder for {}", actualDraftsFolder, email);

        } catch (Exception e) {
            log.error("Failed to save draft to IMAP: {}", e.getMessage(), e);
            throw new MailException("Failed to save draft: " + e.getMessage());
        } finally {
            cleanup(store, draftsFolder);
        }
    }

    public FolderResult getSent(String email, String password, int page, int limit) {
        return getEmailsFromFolder(email, password, "Sent", page, limit);
    }

    public FolderResult getTrash(String email, String password, int page, int limit) {
        return getEmailsFromFolder(email, password, "Trash", page, limit);
    }

    public FolderResult getSpam(String email, String password, int page, int limit) {
        return getEmailsFromFolder(email, password, "Spam", page, limit);
    }

    public FolderResult getSnoozed(String email, String password, int page, int limit) {
        return getEmailsFromFolder(email, password, "Snoozed", page, limit);
    }

    public FolderResult getArchive(String email, String password, int page, int limit) {
        return getEmailsFromFolder(email, password, "Archive", page, limit);
    }

    public FolderResult getEmailsByCategory(String email, String password, String category, int page, int limit) {
        log.info("Fetching emails for category '{}' for: {}", category, email);
        
        // Strategy: Fetch a larger batch from Inbox and filter by calculated category
        int scanLimit = Math.max(limit * 4, 100); 
        List<EmailDTO> allInbox = getInbox(email, password, 1, scanLimit).getEmails();
        
        List<EmailDTO> filtered = allInbox.stream()
                .filter(e -> {
                    if ("UNREAD".equalsIgnoreCase(category)) {
                        return !e.isRead();
                    }
                    return category.equalsIgnoreCase(e.getCategory());
                })
                .collect(java.util.stream.Collectors.toList());

        int totalCount = filtered.size();
        int startIndex = (page - 1) * limit;
        List<EmailDTO> paginated = new ArrayList<>();
        if (startIndex < totalCount) {
            paginated = filtered.subList(startIndex, Math.min(startIndex + limit, totalCount));
        }
        return new FolderResult(paginated, totalCount);
    }



    public FolderResult getEmailsByLabel(String email, String password, Long labelId, int page, int limit) {
        log.info("Fetching emails for label ID {} for: {}", labelId, email);
        
        List<MailLabelMapping> mappings = labelMappingRepository.findByUserEmailAndLabelId(email, labelId);
        if (mappings.isEmpty()) return new FolderResult(new ArrayList<>(), 0);

        int totalCount = mappings.size();
        mappings.sort((a, b) -> b.getId().compareTo(a.getId())); // Newest first

        int startIndex = (page - 1) * limit;
        if (startIndex >= totalCount) return new FolderResult(new ArrayList<>(), totalCount);
        List<MailLabelMapping> pageMappings = mappings.subList(startIndex, Math.min(startIndex + limit, totalCount));

        jakarta.mail.Store store = null;
        try {
            store = connect(email, password);
            List<EmailDTO> labeledEmails = new ArrayList<>();
            
            for (MailLabelMapping mapping : pageMappings) {
                try {
                    String folderToOpen = mapping.getFolderName();
                    if ("Sent".equalsIgnoreCase(folderToOpen)) folderToOpen = resolveSentFolderName(store);
                    else if ("Trash".equalsIgnoreCase(folderToOpen)) folderToOpen = resolveTrashFolderName(store);
                    else if ("Spam".equalsIgnoreCase(folderToOpen)) folderToOpen = resolveSpamFolderName(store);
                    else if ("Archive".equalsIgnoreCase(folderToOpen)) folderToOpen = resolveArchiveFolderName(store);

                    String[] candidates = { folderToOpen, "INBOX", resolveSentFolderName(store), resolveArchiveFolderName(store) };
                    for (String candidate : candidates) {
                        if (candidate == null || candidate.toUpperCase().contains("STARRED") || candidate.toUpperCase().contains("ALL") || candidate.toUpperCase().contains("LABEL")) continue;
                        jakarta.mail.Folder f = null;
                        try {
                            f = store.getFolder(candidate);
                            if (f.exists()) {
                                if (!f.isOpen()) f.open(jakarta.mail.Folder.READ_ONLY);
                                if (f instanceof jakarta.mail.UIDFolder) {
                                    jakarta.mail.UIDFolder uidFolder = (jakarta.mail.UIDFolder) f;
                                    jakarta.mail.Message msg = uidFolder.getMessageByUID(Long.parseLong(mapping.getEmailUid()));
                                    if (msg != null) {
                                        labeledEmails.add(convertToDTO(msg, uidFolder, email));
                                        try { if (f != null && f.isOpen()) f.close(false); } catch (Exception ex) {}
                                        break;
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.debug("Failed candidate {} for label email UID {}", candidate, mapping.getEmailUid());
                        } finally {
                            try { if (f != null && f.isOpen()) f.close(false); } catch (Exception ex) {}
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to fetch labeled email for UID {}: {}", mapping.getEmailUid(), e.getMessage());
                }
            }
            return new FolderResult(labeledEmails, totalCount);
        } catch (jakarta.mail.MessagingException e) {
            log.error("Failed to connect for labeled emails: {}", e.getMessage());
            throw new MailException("Failed to fetch labeled emails.");
        } finally {
            cleanup(store, null);
        }
    }

    public FolderResult getStarred(String email, String password, int page, int limit) {
        log.info("Fetching starred emails for: {}", email);
        
        List<StarredEmail> starredMappings = starredEmailRepository.findByUserEmail(email);
        if (starredMappings.isEmpty()) return new FolderResult(new ArrayList<>(), 0);

        int totalCount = starredMappings.size();
        int startIndex = (page - 1) * limit;
        if (startIndex >= totalCount) return new FolderResult(new ArrayList<>(), totalCount);
        List<StarredEmail> pageMappings = starredMappings.subList(startIndex, Math.min(startIndex + limit, totalCount));

        Store store = null;
        try {
            store = connect(email, password);
            List<EmailDTO> starredEmails = new ArrayList<>();
            
            for (StarredEmail mapping : pageMappings) {
                try {
                    String folderToOpen = mapping.getFolderName();
                    if ("Sent".equalsIgnoreCase(folderToOpen)) folderToOpen = resolveSentFolderName(store);
                    else if ("Trash".equalsIgnoreCase(folderToOpen)) folderToOpen = resolveTrashFolderName(store);
                    else if ("Spam".equalsIgnoreCase(folderToOpen)) folderToOpen = resolveSpamFolderName(store);
                    else if ("Archive".equalsIgnoreCase(folderToOpen)) folderToOpen = resolveArchiveFolderName(store);

                    String[] candidates = { folderToOpen, "INBOX", resolveSentFolderName(store), resolveArchiveFolderName(store) };
                    for (String candidate : candidates) {
                        if (candidate == null || candidate.toUpperCase().contains("STARRED") || candidate.toUpperCase().contains("ALL") || candidate.toUpperCase().contains("LABEL")) continue;
                        Folder f = null;
                        try {
                            f = store.getFolder(candidate);
                            if (f.exists()) {
                                if (!f.isOpen()) f.open(Folder.READ_ONLY);
                                if (f instanceof UIDFolder) {
                                    UIDFolder uidFolder = (UIDFolder) f;
                                    Message msg = uidFolder.getMessageByUID(Long.parseLong(mapping.getUid()));
                                    if (msg != null) {
                                        EmailDTO dto = convertToDTO(msg, uidFolder, email);
                                        dto.setStarred(true); 
                                        starredEmails.add(dto);
                                        try { if (f != null && f.isOpen()) f.close(false); } catch (Exception ex) {}
                                        break;
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.debug("Failed candidate {} for starred email UID {}", candidate, mapping.getUid());
                        } finally {
                            try { if (f != null && f.isOpen()) f.close(false); } catch (Exception ex) {}
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to fetch starred email for UID {}: {}", mapping.getUid(), e.getMessage());
                }
            }
            return new FolderResult(starredEmails, totalCount);
        } catch (MessagingException e) {
            log.error("Failed to connect for starred emails: {}", e.getMessage());
            throw new MailException("Failed to fetch starred emails.");
        } finally {
            cleanup(store, null);
        }
    }

    @Transactional
    public void toggleStar(String email, String password, String folderName, String uid) {
        log.info("Toggling star for email UID {} in folder {} from local DB mapping", uid, folderName);
        List<StarredEmail> list = starredEmailRepository.findByUserEmailAndUid(email, uid);
        
        if (!list.isEmpty()) {
            starredEmailRepository.deleteAll(list);
            log.info("Successfully UNSTARRED UID {}", uid);
        } else {
            String resolvedFolder = getStaticNormalizedFolderName(folderName);
            if (resolvedFolder.toUpperCase().contains("STARRED") || resolvedFolder.toUpperCase().contains("ALLMAIL") || resolvedFolder.toUpperCase().contains("ALL-MAIL") || resolvedFolder.toUpperCase().contains("LABEL")) {
                resolvedFolder = "INBOX";
            }
            StarredEmail star = StarredEmail.builder()
                .userEmail(email)
                .uid(uid)
                .folderName(resolvedFolder)
                .starredAt(java.time.LocalDateTime.now())
                .build();
            starredEmailRepository.save(star);
            log.info("Successfully STARRED UID {} in {}", uid, resolvedFolder);
        }
    }

    public void moveToTrash(String email, String password, String sourceFolderName, String uid) {
        log.info("Moving email UID {} from {} to Trash for {}", uid, sourceFolderName, email);
        moveMessage(email, password, sourceFolderName, uid, "Trash");
    }

    public void markAsSpam(String email, String password, String sourceFolderName, String uid) {
        log.info("Marking email UID {} from {} as SPAM for {}", uid, sourceFolderName, email);
        moveMessage(email, password, sourceFolderName, uid, "Spam");
    }

    public void archiveEmail(String email, String password, String sourceFolder, String uid) {
        log.info("Archiving email UID {} from {} to Archive for {}", uid, sourceFolder, email);
        moveMessage(email, password, sourceFolder, uid, "Archive");
    }

    public void unarchiveEmail(String email, String password, String uid) {
        log.info("Unarchiving email UID {} from Archive to Inbox for {}", uid, email);
        restoreFromFolder(email, password, uid, "Archive");
    }

    @Transactional
    public void snoozeEmail(String email, String password, String sourceFolder, String uid, java.time.LocalDateTime wakeUpAt) {
        log.info("Snoozing email UID {} from {} until {}", uid, sourceFolder, wakeUpAt);
        
        String newUid = moveMessage(email, password, sourceFolder, uid, "Snoozed");
        
        SnoozedEmail snooze = SnoozedEmail.builder()
            .userEmail(email)
            .uid(newUid)
            .originalFolderName(sourceFolder)
            .wakeUpAt(wakeUpAt)
            .build();
            
        snoozedEmailRepository.save(snooze);
    }


    public String moveMessage(String email, String password, String sourceFolderName, String uid, String targetFolderAlias) {
        Store store = null;
        Folder source = null;
        Folder target = null;
        String resultingUid = uid;

        try {
            store = connect(email, password);

            String resolvedTargetFolderName;
            if ("Trash".equalsIgnoreCase(targetFolderAlias)) {
                resolvedTargetFolderName = resolveTrashFolderName(store);
            } else if ("Spam".equalsIgnoreCase(targetFolderAlias)) {
                resolvedTargetFolderName = resolveSpamFolderName(store);
            } else if ("Snoozed".equalsIgnoreCase(targetFolderAlias)) {
                resolvedTargetFolderName = resolveSnoozedFolderName(store);
            } else if ("Archive".equalsIgnoreCase(targetFolderAlias)) {
                resolvedTargetFolderName = resolveArchiveFolderName(store);
            } else {
                resolvedTargetFolderName = targetFolderAlias;
            }


            target = store.getFolder(resolvedTargetFolderName);
            if (!target.exists()) target.create(Folder.HOLDS_MESSAGES);

            String resolvedSourceFolderName = sourceFolderName;
            if (sourceFolderName != null) {
                String upper = sourceFolderName.toUpperCase();
                if (upper.contains("STARRED")) {
                    var list = starredEmailRepository.findByUserEmailAndUid(email, uid);
                    if (!list.isEmpty()) resolvedSourceFolderName = list.get(0).getFolderName();
                    else resolvedSourceFolderName = "INBOX";
                } else if (upper.contains("ALLMAIL") || upper.contains("ALL-MAIL")) {
                    resolvedSourceFolderName = "INBOX";
                } else if (upper.contains("LABEL")) {
                    var list = labelMappingRepository.findByUserEmailAndEmailUid(email, uid);
                    if (!list.isEmpty()) resolvedSourceFolderName = list.get(0).getFolderName();
                    else resolvedSourceFolderName = "INBOX";
                }

                if ("Sent".equalsIgnoreCase(resolvedSourceFolderName)) {
                    resolvedSourceFolderName = resolveSentFolderName(store);
                } else if ("Trash".equalsIgnoreCase(resolvedSourceFolderName)) {
                    resolvedSourceFolderName = resolveTrashFolderName(store);
                } else if ("Spam".equalsIgnoreCase(resolvedSourceFolderName)) {
                    resolvedSourceFolderName = resolveSpamFolderName(store);
                } else if ("Snoozed".equalsIgnoreCase(resolvedSourceFolderName)) {
                    resolvedSourceFolderName = resolveSnoozedFolderName(store);
                } else if ("Archive".equalsIgnoreCase(resolvedSourceFolderName)) {
                    resolvedSourceFolderName = resolveArchiveFolderName(store);
                } else if ("Drafts".equalsIgnoreCase(resolvedSourceFolderName) || "Draft".equalsIgnoreCase(resolvedSourceFolderName)) {
                    resolvedSourceFolderName = resolveDraftsFolderName(store);
                }
            }

            String[] candidateFolders = { resolvedSourceFolderName, "INBOX", resolveSentFolderName(store), resolveArchiveFolderName(store) };
            Message message = null;
            for (String candidate : candidateFolders) {
                if (candidate == null || candidate.toUpperCase().contains("STARRED") || candidate.toUpperCase().contains("ALL") || candidate.toUpperCase().contains("LABEL")) continue;
                try {
                    Folder temp = store.getFolder(candidate);
                    if (temp.exists()) {
                        temp.open(Folder.READ_WRITE);
                        if (temp instanceof UIDFolder) {
                            Message m = ((UIDFolder) temp).getMessageByUID(Long.parseLong(uid));
                            if (m != null) {
                                source = temp;
                                message = m;
                                break;
                            }
                        }
                        temp.close(false);
                    }
                } catch (Exception e) {
                    log.debug("Could not find message in candidate folder {}: {}", candidate, e.getMessage());
                }
            }

            if (message == null || source == null) {
                throw new MailException("Email with UID " + uid + " could not be found in any folder.");
            }

            source.copyMessages(new Message[]{message}, target);
            try {
                target.open(Folder.READ_WRITE);
                if (target instanceof UIDFolder) {
                    int count = target.getMessageCount();
                    if (count > 0) {
                        Message newMsg = target.getMessage(count);
                        String newUidStr = String.valueOf(((UIDFolder) target).getUID(newMsg));
                        resultingUid = newUidStr;
                        log.info("Updating DB mappings from old UID {} to new UID {} in folder {}", uid, newUidStr, resolvedTargetFolderName);
                        
                        List<StarredEmail> stars = starredEmailRepository.findByUserEmailAndUid(email, uid);
                        for (StarredEmail star : stars) {
                            star.setUid(newUidStr);
                            star.setFolderName(resolvedTargetFolderName);
                            starredEmailRepository.save(star);
                        }

                        List<MailLabelMapping> labels = labelMappingRepository.findByUserEmailAndEmailUid(email, uid);
                        for (MailLabelMapping label : labels) {
                            label.setEmailUid(newUidStr);
                            label.setFolderName(resolvedTargetFolderName);
                            labelMappingRepository.save(label);
                        }
                    }
                }
                target.close(false);
            } catch (Exception ex) {
                log.warn("Failed to update UID mapping after copy: {}", ex.getMessage());
            }

            message.setFlag(Flags.Flag.DELETED, true);
            source.expunge();

            log.info("Successfully moved message {} to {}", uid, resolvedTargetFolderName);
            return resultingUid;

        } catch (Exception e) {
            log.error("Failed to move message to {}: {}", targetFolderAlias, e.getMessage(), e);
            throw new MailException("Failed to move message: " + e.getMessage());
        } finally {
            try { if (source != null && source.isOpen()) source.close(true); } catch (Exception e) {}
            try { if (store != null) store.close(); } catch (Exception e) {}
        }
    }


    public void restoreFromTrash(String email, String password, String uid) {
        log.info("Restoring email UID {} from Trash to Inbox for {}", uid, email);
        restoreFromFolder(email, password, uid, "Trash");
    }

    public void restoreFromSpam(String email, String password, String uid) {
        log.info("Restoring email UID {} from Spam to Inbox for {}", uid, email);
        restoreFromFolder(email, password, uid, "Spam");
    }

    private void restoreFromFolder(String email, String password, String uid, String sourceAlias) {
        Store store = null;
        Folder source = null;
        Folder inbox = null;

        try {
            store = connect(email, password);

            String solvedSource;
            if ("Trash".equalsIgnoreCase(sourceAlias)) solvedSource = resolveTrashFolderName(store);
            else if ("Spam".equalsIgnoreCase(sourceAlias)) solvedSource = resolveSpamFolderName(store);
            else if ("Archive".equalsIgnoreCase(sourceAlias)) solvedSource = resolveArchiveFolderName(store);
            else solvedSource = sourceAlias;

            source = store.getFolder(solvedSource);
            source.open(Folder.READ_WRITE);

            if (!(source instanceof UIDFolder)) {
                throw new MailException(sourceAlias + " folder does not support persistent UIDs.");
            }
            UIDFolder uidSource = (UIDFolder) source;

            long numericUid = Long.parseLong(uid);
            Message message = uidSource.getMessageByUID(numericUid);

            if (message == null) {
                throw new MailException("Email with UID " + uid + " no longer exists in " + sourceAlias);
            }

            boolean isDraft = message.isSet(Flags.Flag.DRAFT);
            boolean isSentMessage = false;
            
            if (!isDraft) {
                Address[] fromAddresses = message.getFrom();
                if (fromAddresses != null) {
                    for (Address address : fromAddresses) {
                        if (address instanceof InternetAddress) {
                            String fromEmail = ((InternetAddress) address).getAddress();
                            if (email.equalsIgnoreCase(fromEmail)) {
                                isSentMessage = true;
                                break;
                            }
                        }
                    }
                }
            }

            String targetFolderName;
            if (isDraft) {
                targetFolderName = resolveDraftsFolderName(store);
            } else if (isSentMessage) {
                targetFolderName = resolveSentFolderName(store);
            } else {
                targetFolderName = "INBOX";
            }
            inbox = store.getFolder(targetFolderName);
            if (!inbox.exists()) inbox.create(Folder.HOLDS_MESSAGES);

            source.copyMessages(new Message[]{message}, inbox);
            try {
                inbox.open(Folder.READ_WRITE);
                if (inbox instanceof UIDFolder) {
                    int count = inbox.getMessageCount();
                    if (count > 0) {
                        Message newMsg = inbox.getMessage(count);
                        String newUidStr = String.valueOf(((UIDFolder) inbox).getUID(newMsg));
                        log.info("Updating DB mappings from old UID {} to new UID {} in folder {}", uid, newUidStr, targetFolderName);
                        
                        List<StarredEmail> stars = starredEmailRepository.findByUserEmailAndUid(email, uid);
                        for (StarredEmail star : stars) {
                            star.setUid(newUidStr);
                            star.setFolderName(targetFolderName);
                            starredEmailRepository.save(star);
                        }

                        List<MailLabelMapping> labels = labelMappingRepository.findByUserEmailAndEmailUid(email, uid);
                        for (MailLabelMapping label : labels) {
                            label.setEmailUid(newUidStr);
                            label.setFolderName(targetFolderName);
                            labelMappingRepository.save(label);
                        }
                    }
                }
                inbox.close(false);
            } catch (Exception ex) {
                log.warn("Failed to update UID mapping after restore: {}", ex.getMessage());
            }

            message.setFlag(Flags.Flag.DELETED, true);
            source.expunge();

            log.info("Successfully restored message {} from {} to {}", uid, sourceAlias, targetFolderName);

        } catch (Exception e) {
            log.error("Failed to restore from {}: {}", sourceAlias, e.getMessage(), e);
            throw new MailException("Failed to restore from " + sourceAlias + ": " + e.getMessage());
        } finally {
            try { if (source != null && source.isOpen()) source.close(true); } catch (Exception e) {}
            try { if (store != null) store.close(); } catch (Exception e) {}
        }
    }

    public void deletePermanently(String email, String password, String uid) {
        log.info("Permanently deleting email UID {} from Trash for {}", uid, email);
        Store store = null;
        Folder trash = null;

        try {
            store = connect(email, password);

            String trashName = resolveTrashFolderName(store);
            trash = store.getFolder(trashName);
            trash.open(Folder.READ_WRITE);

            if (!(trash instanceof UIDFolder)) {
                throw new MailException("Trash folder does not support persistent UIDs.");
            }
            UIDFolder uidTrash = (UIDFolder) trash;

            long numericUid = Long.parseLong(uid);
            Message message = uidTrash.getMessageByUID(numericUid);

            if (message == null) {
                throw new MailException("Email with UID " + uid + " no longer exists in Trash");
            }

            message.setFlag(Flags.Flag.DELETED, true);
            trash.expunge();

            log.info("Successfully deleted message {} permanently", uid);

        } catch (Exception e) {
            log.error("Failed to delete permanently: {}", e.getMessage(), e);
            throw new MailException("Failed to delete permanently: " + e.getMessage());
        } finally {
            try { if (trash != null && trash.isOpen()) trash.close(true); } catch (Exception e) {}
            try { if (store != null) store.close(); } catch (Exception e) {}
        }
    }

    public void markAsRead(String email, String password, String uid) {
        setSeenFlag(email, password, uid, true);
    }

    public void markAsUnread(String email, String password, String uid) {
        setSeenFlag(email, password, uid, false);
    }

    private void setSeenFlag(String email, String password, String uid, boolean seen) {
        jakarta.mail.Store store = null;
        jakarta.mail.Folder inbox = null;
        try {
            store = connect(email, password);

            inbox = store.getFolder("INBOX");
            inbox.open(jakarta.mail.Folder.READ_WRITE);

            if (!(inbox instanceof jakarta.mail.UIDFolder)) {
                throw new MailException("INBOX does not support persistent UIDs.");
            }
            jakarta.mail.UIDFolder uidInbox = (jakarta.mail.UIDFolder) inbox;

            long numericUid = Long.parseLong(uid);
            jakarta.mail.Message message = uidInbox.getMessageByUID(numericUid);

            if (message == null) {
                throw new MailException("Email with UID " + uid + " no longer exists in INBOX");
            }

            message.setFlag(jakarta.mail.Flags.Flag.SEEN, seen);
            log.info("Marked message {} as {} for {}", uid, seen ? "read" : "unread", email);
        } catch (Exception e) {
            log.error("Failed to set seen flag to {}: {}", seen, e.getMessage(), e);
            throw new MailException("Failed to update read status: " + e.getMessage());
        } finally {
            cleanup(store, inbox);
        }
    }

    public int getUnreadCount(String email, String password) {
        Store store = null;
        Folder inbox = null;
        try {
            store = connect(email, password);
            processUnsubscribedEmails(store, email);

            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);

            Map<String, LocalDateTime> blockedMap = new HashMap<>();
            blockedContactRepository.findByUserEmail(email).forEach(c ->
                    blockedMap.put(c.getBlockedEmail().toLowerCase(), c.getBlockedAt())
            );

            jakarta.mail.search.SearchTerm searchFlag = new jakarta.mail.search.FlagTerm(new Flags(Flags.Flag.SEEN), false);
            Message[] messages = inbox.search(searchFlag);

            int unreadCount = 0;
            for (Message msg : messages) {
                try {
                    String subject = msg.getSubject();
                    if (subject != null && subject.toLowerCase().contains("[colab#")) {
                        continue;
                    }
                    if (!blockedMap.isEmpty()) {
                        Address[] from = msg.getFrom();
                        if (from != null && from.length > 0) {
                            String cleanFrom = extractEmailAddress(from[0].toString());
                            if (cleanFrom != null) {
                                String cleanLower = cleanFrom.toLowerCase();
                                if (blockedMap.containsKey(cleanLower)) {
                                    LocalDateTime blockedAt = blockedMap.get(cleanLower);
                                    java.util.Date sentDate = msg.getSentDate();
                                    LocalDateTime msgTime = (sentDate != null) ?
                                            sentDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime() :
                                            LocalDateTime.now();
                                    if (!msgTime.isBefore(blockedAt)) {
                                        continue;
                                    }
                                }
                            }
                        }
                    }
                    unreadCount++;
                } catch (Exception e) {
                    log.warn("Failed to check unread message status: {}", e.getMessage());
                }
            }
            return unreadCount;
        } catch (Exception e) {
            log.error("Failed to get unread count: {}", e.getMessage());
            return 0;
        } finally {
            cleanup(store, inbox);
        }
    }

    public FolderResult getUnreadEmails(String email, String password, int page, int limit) {
        log.info("Fetching unread messages from INBOX for: {}", email);

        Store store = null;
        Folder folder = null;

        try {
            store = connect(email, password);
            processUnsubscribedEmails(store, email);
            folder = store.getFolder("INBOX");
            
            if (!folder.exists()) {
                log.warn("⚠ INBOX folder missing for user {}. Returning empty list.", email);
                return new FolderResult(new ArrayList<>(), 0);
            }
            
            folder.open(Folder.READ_ONLY);
            log.info("Successfully opened INBOX for unread. Message count: {}", folder.getMessageCount());

            UIDFolder uidFolder = (folder instanceof UIDFolder) ? (UIDFolder) folder : null;

            Map<String, LocalDateTime> blockedMap = new HashMap<>();
            blockedContactRepository.findByUserEmail(email).forEach(c ->
                    blockedMap.put(c.getBlockedEmail().toLowerCase(), c.getBlockedAt())
            );

            // Search for unseen messages
            jakarta.mail.search.SearchTerm searchFlag = new jakarta.mail.search.FlagTerm(new Flags(Flags.Flag.SEEN), false);
            Message[] messages = folder.search(searchFlag);

            if (messages == null || messages.length == 0) {
                return new FolderResult(new ArrayList<>(), 0);
            }

            // Filter out blocked senders and Colab messages
            List<Message> filteredMessages = new ArrayList<>();
            for (Message msg : messages) {
                try {
                    String subject = msg.getSubject();
                    if (subject != null && subject.toLowerCase().contains("[colab#")) {
                        continue;
                    }
                    if (!blockedMap.isEmpty()) {
                        Address[] from = msg.getFrom();
                        if (from != null && from.length > 0) {
                            String cleanFrom = extractEmailAddress(from[0].toString());
                            if (cleanFrom != null) {
                                String cleanLower = cleanFrom.toLowerCase();
                                if (blockedMap.containsKey(cleanLower)) {
                                    LocalDateTime blockedAt = blockedMap.get(cleanLower);
                                    java.util.Date sentDate = msg.getSentDate();
                                    LocalDateTime msgTime = (sentDate != null) ?
                                            sentDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime() :
                                            LocalDateTime.now();
                                    if (!msgTime.isBefore(blockedAt)) {
                                        continue;
                                    }
                                }
                            }
                        }
                    }
                    filteredMessages.add(msg);
                } catch (Exception e) {
                    log.warn("Failed to check message for unread filter: {}", e.getMessage());
                }
            }

            int totalCount = filteredMessages.size();
            if (totalCount == 0) {
                return new FolderResult(new ArrayList<>(), 0);
            }

            // Sort: newer first (search results are normally older first).
            // Calculate paginated sub-list bounds
            int endIndex = totalCount - (page - 1) * limit;
            int startIndex = Math.max(0, endIndex - limit);

            List<EmailDTO> emails = new ArrayList<>();
            if (endIndex > 0) {
                for (int i = endIndex - 1; i >= startIndex; i--) {
                    try {
                        Message msg = filteredMessages.get(i);
                        emails.add(convertToDTO(msg, uidFolder, email));
                    } catch (Exception e) {
                        log.warn("Failed to convert unread message to DTO: {}", e.getMessage());
                    }
                }
            }

            return new FolderResult(emails, totalCount);

        } catch (MessagingException e) {
            log.error("Failed to fetch unread emails: {}", e.getMessage(), e);
            throw new MailException("Failed to fetch unread emails: " + e.getMessage());
        } finally {
            cleanup(store, folder);
        }
    }

    public EmailDTO getEmail(String email, String password, String uid) {
        log.info("Fetching single email details for UID: {}", uid);
        Store store = null;
        Folder inbox = null;
        try {
            store = connect(email, password);
            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);

            if (!(inbox instanceof UIDFolder)) {
                throw new MailException("INBOX does not support persistent UIDs.");
            }
            UIDFolder uidInbox = (UIDFolder) inbox;

            long numericUid = Long.parseLong(uid);
            Message message = uidInbox.getMessageByUID(numericUid);

            if (message == null) {
                throw new MailException("Email with UID " + uid + " not found");
            }

            return convertToDTO(message, uidInbox, email);
        } catch (Exception e) {
            log.error("Failed to fetch email details: {}", e.getMessage());
            throw new MailException("Failed to fetch email: " + e.getMessage());
        } finally {
            cleanup(store, inbox);
        }
    }

    private String resolveTrashFolderName(Store store) throws MessagingException {
        String[] candidates = {"Trash", "TRASH", "Deleted Items", "Deleted", "INBOX.Trash", "INBOX/Trash"};
        for (String name : candidates) {
            try { 
                Folder f = store.getFolder(name);
                if (f.exists()) return name; 
            } catch (Exception e) {
                log.debug("Folder candidate '{}' check failed: {}", name, e.getMessage());
            }
        }
        try {
            Folder[] folders = store.getDefaultFolder().list("*");
            for (Folder f : folders) {
                String name = f.getFullName();
                if (name.equalsIgnoreCase("Trash") || name.toUpperCase().contains("TRASH") || name.toUpperCase().contains("DELETED")) return name;
            }
        } catch (MessagingException e) {
            log.warn("Failed to list all folders for trash resolution, falling back to 'Trash'");
        }
        return "Trash";
    }

    private String resolveSpamFolderName(Store store) throws MessagingException {
        String[] candidates = {"Spam", "SPAM", "Junk", "JUNK", "Junk Email", "INBOX.Spam", "INBOX.Junk", "[Gmail]/Spam"};
        for (String name : candidates) {
            try { 
                Folder f = store.getFolder(name);
                if (f.exists()) return name; 
            } catch (Exception e) {
                log.debug("Folder candidate '{}' check failed: {}", name, e.getMessage());
            }
        }
        try {
            Folder[] folders = store.getDefaultFolder().list("*");
            for (Folder f : folders) {
                String name = f.getFullName();
                if (name.equalsIgnoreCase("Spam") || name.toUpperCase().contains("SPAM") || name.toUpperCase().contains("JUNK")) return name;
            }
        } catch (MessagingException e) {
            log.warn("Failed to list all folders for spam resolution, falling back to 'Spam'");
        }
        return "Spam";
    }

    private String resolveSnoozedFolderName(Store store) throws MessagingException {
        String[] candidates = {"Snoozed", "SNOOZED", "INBOX.Snoozed", "INBOX/Snoozed", "Snooze"};
        for (String name : candidates) {
            try { 
                Folder f = store.getFolder(name);
                if (f.exists()) return name; 
            } catch (Exception e) {
                log.debug("Folder candidate '{}' check failed: {}", name, e.getMessage());
            }
        }
        
        // Scan for anything similar
        try {
            Folder[] folders = store.getDefaultFolder().list("*");
            for (Folder f : folders) {
                String name = f.getFullName();
                if (name.equalsIgnoreCase("Snoozed") || name.toUpperCase().contains("SNOOZE")) return name;
            }
        } catch (MessagingException e) {
            log.warn("Failed to scan folders for snoozed resolution");
        }

        // If not found, try to create it. Prefer INBOX prefix if candidates failed
        log.info("Snoozed folder not found, attempting to create 'Snoozed'");
        try {
            Folder f = store.getFolder("Snoozed");
            if (!f.exists()) f.create(Folder.HOLDS_MESSAGES);
            return "Snoozed";
        } catch (MessagingException e) {
            log.warn("Failed to create 'Snoozed' at root, trying 'INBOX.Snoozed'");
            Folder f = store.getFolder("INBOX.Snoozed");
            if (!f.exists()) f.create(Folder.HOLDS_MESSAGES);
            return "INBOX.Snoozed";
        }
    }

    private String resolveArchiveFolderName(Store store) throws MessagingException {
        String[] candidates = {"Archive", "ARCHIVE", "INBOX.Archive", "INBOX/Archive", "Archived", "INBOX.Archived"};
        for (String name : candidates) {
            try { 
                Folder f = store.getFolder(name);
                if (f.exists()) return name; 
            } catch (Exception e) {
                log.debug("Folder candidate '{}' check failed: {}", name, e.getMessage());
            }
        }
        try {
            Folder[] folders = store.getDefaultFolder().list("*");
            for (Folder f : folders) {
                String name = f.getFullName();
                if (name.equalsIgnoreCase("Archive") || name.toUpperCase().contains("ARCHIVE")) return name;
            }
        } catch (MessagingException e) {
            log.warn("Failed to list all folders for archive resolution");
        }
        return "Archive";
    }

    public String resolveDraftsFolderName(Store store) throws MessagingException {
        String[] candidates = {
            "Drafts", "DRAFTS", "Draft", "Draft Messages", 
            "INBOX.Drafts", "INBOX/Drafts", "[Gmail]/Drafts", "Drafts Items",
            "INBOX.Draft", "INBOX/Draft", "Drafts messages"
        };
        for (String name : candidates) {
            try { 
                Folder f = store.getFolder(name);
                if (f.exists()) return name; 
            } catch (Exception e) {
                log.debug("Folder candidate '{}' check failed: {}", name, e.getMessage());
            }
        }
        try {
            Folder[] folders = store.getDefaultFolder().list("*");
            for (Folder f : folders) {
                String name = f.getFullName();
                if (name.equalsIgnoreCase("Drafts") || name.toUpperCase().contains("DRAFT")) return name;
            }
        } catch (MessagingException e) {
            log.warn("Failed to list all folders for drafts resolution, falling back to 'Drafts'");
        }
        return "Drafts";
    }

    public String resolveSentFolderName(Store store) throws MessagingException {
        // Broad list of shared folder names across various IMAP servers
        String[] candidates = {
            "Sent", "SENT", "Sent Messages", "Sent Items", 
            "INBOX.Sent", "INBOX/Sent", "[Gmail]/Sent Mail", "Sent Mail",
            "Sent Items", "SentMessages", "Outbox", "Sent Items", "Sent messages"
        };
        
        for (String name : candidates) {
            try { 
                Folder f = store.getFolder(name);
                if (f.exists()) {
                    log.debug("✓ Found Sent folder among candidates: {}", name);
                    return name;
                }
            } catch (Exception e) {
                log.trace("Folder candidate '{}' check failed: {}", name, e.getMessage());
            }
        }

        // Fallback: Scan ALL folders recursively
        try {
            log.info("Sent folder not in candidates, scanning ALL IMAP folders recursively...");
            Folder defaultFolder = store.getDefaultFolder();
            
            // Level 1 scan
            Folder[] folders = defaultFolder.list("*");
            for (Folder f : folders) {
                String name = f.getFullName();
                if (name.equalsIgnoreCase("Sent") || name.toUpperCase().contains("SENT")) {
                    log.info("✓ Found fallback Sent folder match via scan: {}", name);
                    return name;
                }
            }
            
            // Deep scan if needed
            for (Folder f : folders) {
                if ((f.getType() & Folder.HOLDS_FOLDERS) != 0) {
                    Folder[] sub = f.list("*");
                    for (Folder s : sub) {
                        String name = s.getFullName();
                        if (name.toUpperCase().contains("SENT")) {
                            log.info("✓ Found deep Sent folder match: {}", name);
                            return name;
                        }
                    }
                }
            }
            
            // Special check for INBOX prefix if not already found
            Folder inbox = store.getFolder("INBOX");
            if (inbox.exists()) {
                Folder[] subfolders = inbox.list("*");
                for (Folder f : subfolders) {
                    String name = f.getFullName();
                    if (name.toUpperCase().contains("SENT")) {
                        log.info("✓ Found match via INBOX scan: {}", name);
                        return name;
                    }
                }
            }
        } catch (MessagingException e) {
             log.warn("Failed to list all folders for sent resolution: {}", e.getMessage());
        }

        log.warn("⚠ NO SENT FOLDER DETECTED for user. Using default 'Sent'.");
        return "Sent";
    }

    private String getStaticNormalizedFolderName(String folderName) {
        if (folderName == null) return "INBOX";
        String upper = folderName.toUpperCase();
        if (upper.contains("INBOX")) return "INBOX";
        if (upper.contains("SENT")) return "Sent";
        if (upper.contains("TRASH") || upper.contains("DELETED")) return "Trash";
        if (upper.contains("SPAM") || upper.contains("JUNK")) return "Spam";
        if (upper.contains("SNOOZED")) return "Snoozed";
        if (upper.contains("ARCHIVE")) return "Archive";
        return folderName;
    }


    private EmailDTO convertToDTO(Message message, UIDFolder uidFolder, String userEmail) throws MessagingException, IOException {
        EmailDTO dto = new EmailDTO();
        
        String uidStr;
        // Use persistent UID if available, fallback to message number
        if (uidFolder != null) {
            uidStr = String.valueOf(uidFolder.getUID(message));
        } else {
            uidStr = String.valueOf(message.getMessageNumber());
        }
        dto.setUid(uidStr);

        Address[] from = message.getFrom();
        if (from != null && from.length > 0) {
            Address addr = from[0];
            if (addr instanceof InternetAddress) {
                dto.setFrom(addr.toString());
            } else {
                dto.setFrom(addr.toString());
            }
            // Populate avatar URL based on sender's email address
            String cleanFrom = extractEmailAddress(dto.getFrom());
            if (cleanFrom != null) {
                dto.setAvatarUrl("/api/users/profile-picture/" + cleanFrom);
            }
        }

        Address[] to = message.getRecipients(Message.RecipientType.TO);
        if (to != null && to.length > 0) {
            StringBuilder toStr = new StringBuilder();
            for (Address addr : to) {
                if (toStr.length() > 0) toStr.append(", ");
                toStr.append(addr.toString());
            }
            dto.setTo(toStr.toString());
        }

        Address[] cc = message.getRecipients(Message.RecipientType.CC);
        if (cc != null && cc.length > 0) {
            StringBuilder ccStr = new StringBuilder();
            for (Address addr : cc) {
                if (ccStr.length() > 0) ccStr.append(", ");
                ccStr.append(addr.toString());
            }
            dto.setCc(ccStr.toString());
        }

        Address[] bcc = message.getRecipients(Message.RecipientType.BCC);
        if (bcc != null && bcc.length > 0) {
            StringBuilder bccStr = new StringBuilder();
            for (Address addr : bcc) {
                if (bccStr.length() > 0) bccStr.append(", ");
                bccStr.append(addr.toString());
            }
            dto.setBcc(bccStr.toString());
        }

        // If the user's email is not in To, Cc, or From, assume they were BCC'd
        // Note: We only add it if it's not already in the BCC string
        if (userEmail != null && !userEmail.trim().isEmpty()) {
            String lowerEmail = userEmail.toLowerCase().trim();
            boolean inTo = dto.getTo() != null && dto.getTo().toLowerCase().contains(lowerEmail);
            boolean inCc = dto.getCc() != null && dto.getCc().toLowerCase().contains(lowerEmail);
            boolean inFrom = dto.getFrom() != null && dto.getFrom().toLowerCase().contains(lowerEmail);
            
            if (!inTo && !inCc && !inFrom) {
                String currentBcc = dto.getBcc();
                if (currentBcc == null || currentBcc.trim().isEmpty()) {
                    dto.setBcc(userEmail);
                } else if (!currentBcc.toLowerCase().contains(lowerEmail)) {
                    dto.setBcc(currentBcc + ", " + userEmail);
                }
            }
        }
        
        dto.setSubject(message.getSubject());
        dto.setSentDate(message.getSentDate());
        dto.setReceivedDate(message.getReceivedDate());
        dto.setRead(message.isSet(Flags.Flag.SEEN));
        
        // Use Database mapping for star status
        String actualFolderName = "INBOX";
        try {
            Folder f = message.getFolder();
            if (f != null) {
                actualFolderName = f.getFullName();
            }
        } catch (Exception e) {
            log.debug("Could not determine folder for message: {}", e.getMessage());
        }
        
        String normFolder = getStaticNormalizedFolderName(actualFolderName);
        dto.setFolderName(normFolder);
        dto.setStarred(!starredEmailRepository.findByUserEmailAndUid(userEmail, uidStr).isEmpty());
        dto.setSize(message.getSize());
        
        String[] content = extractContent(message);
        dto.setBody(content[0]);
        dto.setHtmlBody(content[1]);
        dto.setHasAttachments(hasAttachments(message));
        if (dto.isHasAttachments()) dto.setAttachments(extractAttachments(message));
        
        String category = categorizeEmail(message);
        
        // Override category based on actual folder location
        String upperFolder = actualFolderName.toUpperCase();
        if (upperFolder.contains("SENT")) category = "SENT";
        else if (upperFolder.contains("TRASH") || upperFolder.contains("DELETED")) category = "TRASH";
        else if (upperFolder.contains("SPAM") || upperFolder.contains("JUNK")) category = "SPAM";
        else if (upperFolder.contains("DRAFT")) category = "DRAFTS";
        
        dto.setCategory(category);
        // Labels (V3)
        dto.setLabels(labelMappingRepository.findByUserEmailAndEmailUid(userEmail, dto.getUid())
                .stream()
                .map(MailLabelMapping::getLabel)
                .distinct()
                .collect(java.util.stream.Collectors.toList()));

        String msgId = extractMessageId(message);
        if (msgId == null || msgId.trim().isEmpty()) {
            msgId = generateEmailFingerprint(dto.getFrom(), userEmail, dto.getSentDate(), dto.getReceivedDate(), dto.getSubject(), dto.getBody());
        }
        dto.setMessageId(msgId);

        return dto;
    }

    public String extractMessageId(Message message) {
        if (message == null) return null;
        try {
            if (message instanceof MimeMessage mimeMsg) {
                String msgId = mimeMsg.getMessageID();
                if (msgId != null && !msgId.trim().isEmpty()) {
                    return cleanMessageId(msgId);
                }
            }
            String[] headers = message.getHeader("Message-ID");
            if (headers != null && headers.length > 0 && headers[0] != null && !headers[0].trim().isEmpty()) {
                return cleanMessageId(headers[0]);
            }
        } catch (Exception e) {
            log.debug("Could not extract Message-ID: {}", e.getMessage());
        }
        return null;
    }

    public String cleanMessageId(String messageId) {
        if (messageId == null) return null;
        String clean = messageId.trim();
        if (clean.startsWith("<") && clean.endsWith(">")) {
            clean = clean.substring(1, clean.length() - 1).trim();
        }
        return clean;
    }

    public String sha256Hex(String text) {
        if (text == null) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }

    public String generateEmailFingerprint(String sender, String recipient, java.util.Date sentDate, java.util.Date receivedDate, String subject, String body) {
        String cleanSender = sender != null ? sender.trim().toLowerCase() : "";
        String cleanRecipient = recipient != null ? recipient.trim().toLowerCase() : "";
        long timestamp = sentDate != null ? sentDate.getTime() : (receivedDate != null ? receivedDate.getTime() : 0L);
        String cleanSubject = subject != null ? subject.trim() : "";
        String cleanBodyHash = body != null ? sha256Hex(body.trim()) : "";

        return "fp:" + sha256Hex(cleanSender + "|" + cleanRecipient + "|" + timestamp + "|" + cleanSubject + "|" + cleanBodyHash);
    }

    public String getMessageIdentifier(Message message, String recipient) {
        String msgId = extractMessageId(message);
        if (msgId != null && !msgId.isEmpty()) {
            return msgId;
        }
        try {
            Address[] from = message.getFrom();
            String sender = from != null && from.length > 0 ? extractEmailAddress(from[0].toString()) : "";
            java.util.Date sentDate = message.getSentDate();
            java.util.Date receivedDate = message.getReceivedDate();
            String subject = message.getSubject();
            String[] content = extractContent(message);
            String body = content != null && content.length > 0 ? content[0] : "";
            return generateEmailFingerprint(sender, recipient, sentDate, receivedDate, subject, body);
        } catch (Exception e) {
            log.warn("Failed to generate fallback fingerprint: {}", e.getMessage());
            return "msg-fp-" + message.getMessageNumber();
        }
    }

    /**
     * Process incoming emails from unsubscribed senders in INBOX:
     * Moves them to SPAM idempotently with duplicate prevention check.
     */
    private void processUnsubscribedEmails(Store store, String userEmail) {
        if (store == null || userEmail == null || userEmail.trim().isEmpty()) return;

        List<BlockedContact> blockedList = blockedContactRepository.findByUserEmail(userEmail);
        if (blockedList == null || blockedList.isEmpty()) {
            return;
        }

        Map<String, LocalDateTime> blockedMap = new HashMap<>();
        for (BlockedContact c : blockedList) {
            if (c.getBlockedEmail() != null) {
                blockedMap.put(c.getBlockedEmail().toLowerCase().trim(), c.getBlockedAt() != null ? c.getBlockedAt() : LocalDateTime.MIN);
            }
        }
        if (blockedMap.isEmpty()) return;

        Folder inbox = null;
        Folder spamFolder = null;
        try {
            inbox = store.getFolder("INBOX");
            if (!inbox.exists() || inbox.getMessageCount() == 0) {
                return;
            }

            inbox.open(Folder.READ_WRITE);

            String spamName = resolveSpamFolderName(store);
            spamFolder = store.getFolder(spamName);
            if (!spamFolder.exists()) {
                spamFolder.create(Folder.HOLDS_MESSAGES);
            }
            if (!spamFolder.isOpen()) {
                spamFolder.open(Folder.READ_WRITE);
            }

            // Preload existing message identifiers in Spam to prevent duplicates
            Set<String> existingSpamIdentifiers = new HashSet<>();
            try {
                int spamCount = spamFolder.getMessageCount();
                if (spamCount > 0) {
                    Message[] spamMsgs = spamFolder.getMessages();
                    FetchProfile fp = new FetchProfile();
                    fp.add(FetchProfile.Item.ENVELOPE);
                    fp.add("Message-ID");
                    spamFolder.fetch(spamMsgs, fp);
                    for (Message sm : spamMsgs) {
                        try {
                            String id = extractMessageId(sm);
                            if (id != null && !id.isEmpty()) {
                                existingSpamIdentifiers.add(id);
                            }
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                log.warn("Could not preload spam identifiers: {}", e.getMessage());
            }

            Message[] messages = inbox.getMessages();
            boolean inboxModified = false;

            for (Message msg : messages) {
                try {
                    if (msg.isSet(Flags.Flag.DELETED)) {
                        continue;
                    }
                    Address[] from = msg.getFrom();
                    if (from == null || from.length == 0) continue;
                    String cleanFrom = extractEmailAddress(from[0].toString());
                    if (cleanFrom == null) continue;
                    String senderLower = cleanFrom.toLowerCase().trim();

                    if (blockedMap.containsKey(senderLower)) {
                        LocalDateTime blockedAt = blockedMap.get(senderLower);
                        java.util.Date sentDate = msg.getSentDate();
                        LocalDateTime msgTime = (sentDate != null) ?
                                sentDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime() :
                                LocalDateTime.now();

                        if (!msgTime.isBefore(blockedAt)) {
                            // Message is from an unsubscribed sender
                            String messageId = getMessageIdentifier(msg, userEmail);

                            boolean alreadyProcessedInDb = processedEmailRepository.existsByUserEmailAndMessageIdentifier(userEmail, messageId);
                            boolean alreadyInSpam = (messageId != null && existingSpamIdentifiers.contains(messageId));

                            if (alreadyProcessedInDb || alreadyInSpam) {
                                log.info("Message [{}] already processed/exists in Spam for recipient {}. Skipping duplicate insert.", messageId, userEmail);
                            } else {
                                inbox.copyMessages(new Message[]{msg}, spamFolder);
                                log.info("✓ Moved email [{}] from unsubscribed sender {} to Spam for recipient {}", messageId, senderLower, userEmail);
                                if (messageId != null) {
                                    existingSpamIdentifiers.add(messageId);
                                }

                                try {
                                    ProcessedEmail pe = ProcessedEmail.builder()
                                            .userEmail(userEmail)
                                            .messageIdentifier(messageId != null ? messageId : ("msg-" + System.currentTimeMillis()))
                                            .senderEmail(senderLower)
                                            .subject(msg.getSubject())
                                            .folder("SPAM")
                                            .processedAt(LocalDateTime.now())
                                            .build();
                                    processedEmailRepository.save(pe);
                                } catch (Exception dbEx) {
                                    log.warn("Processed email save note: {}", dbEx.getMessage());
                                }
                            }

                            // Mark deleted in INBOX so it is removed from INBOX
                            msg.setFlag(Flags.Flag.DELETED, true);
                            inboxModified = true;
                        }
                    }
                } catch (Exception ex) {
                    log.warn("Error processing inbox message for unsubscribe check: {}", ex.getMessage());
                }
            }

            if (inboxModified) {
                inbox.expunge();
            }

        } catch (Exception e) {
            log.error("Failed to process unsubscribed emails for user {}: {}", userEmail, e.getMessage(), e);
        } finally {
            try { if (spamFolder != null && spamFolder.isOpen()) spamFolder.close(false); } catch (Exception ignored) {}
            try { if (inbox != null && inbox.isOpen()) inbox.close(true); } catch (Exception ignored) {}
        }
    }

    private String extractEmailAddress(String fromHeader) {
        if (fromHeader == null) return null;
        if (fromHeader.contains("<") && fromHeader.contains(">")) {
            return fromHeader.substring(fromHeader.indexOf("<") + 1, fromHeader.indexOf(">")).trim();
        }
        return fromHeader.trim();
    }




    private String categorizeEmail(Message message) {
        if (message == null) return "PRIMARY";
        try {
            // --- Phase 1: Extract Base Data & Headers ---
            String subject = "";
            try { 
                subject = message.getSubject() != null ? message.getSubject().toUpperCase() : "";
            } catch (Exception e) {
                log.debug("Could not read subject for categorization: {}", e.getMessage());
            }

            String from = "";
            String fromLocal = "";
            String fromDomain = "";
            try {
                Address[] fromAddresses = message.getFrom();
                if (fromAddresses != null && fromAddresses.length > 0) {
                    from = fromAddresses[0].toString().toLowerCase();
                    // Extract local and domain
                    String cleanFrom = extractEmailAddress(from);
                    if (cleanFrom != null && cleanFrom.contains("@")) {
                        String[] parts = cleanFrom.split("@");
                        if (parts.length == 2) {
                            fromLocal = parts[0];
                            fromDomain = parts[1];
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Could not read sender for categorization: {}", e.getMessage());
            }

            // Extract headers
            boolean hasUnsubscribe = false;
            boolean isBulk = false;
            boolean isAutoSubmitted = false;
            try {
                String[] unsubHeaders = message.getHeader("List-Unsubscribe");
                if (unsubHeaders != null && unsubHeaders.length > 0) hasUnsubscribe = true;

                String[] precHeaders = message.getHeader("Precedence");
                if (precHeaders != null && precHeaders.length > 0) {
                    for (String h : precHeaders) {
                        if (h.toLowerCase().contains("bulk") || h.toLowerCase().contains("list")) isBulk = true;
                    }
                }

                String[] autoHeaders = message.getHeader("Auto-Submitted");
                if (autoHeaders != null && autoHeaders.length > 0) {
                    for (String h : autoHeaders) {
                        if (h.toLowerCase().contains("auto-generated") || h.toLowerCase().contains("auto-replied")) isAutoSubmitted = true;
                    }
                }
            } catch (Exception e) {
                log.debug("Could not read headers for categorization: {}", e.getMessage());
            }

            // --- Phase 2: Waterfall Classification ---

            // 1. SPAM Detection (Highest Priority)
            if (subject.contains("LOTTERY") || subject.contains("WINNER") || 
                subject.contains("INHERITANCE") || subject.contains("VIAGRA") || 
                subject.contains("CASINO") || subject.contains("JACKPOT") ||
                subject.contains("URGENT ACTION REQUIRED") || subject.contains("ACCOUNT SUSPENDED") ||
                subject.contains("YOU'VE WON") || subject.contains("CONGRATULATIONS") ||
                subject.contains("MILLION DOLLARS") || subject.contains("DEAR WINNER") ||
                subject.contains("DONATION") || subject.contains("FUND TRANSFERS") ||
                subject.contains("LOAN OFFER") || subject.contains("FAST CASH") ||
                subject.contains("EARN MONEY") || subject.contains("WORK FROM HOME") ||
                subject.contains("BITCOIN") || subject.contains("CRYPTO") ||
                subject.contains("INVESTMENT OPPORTUNITY") || subject.contains("MAKE MONEY") ||
                subject.contains("PHARMACY") || subject.contains("CIALIS") ||
                subject.contains("MEDS ONLINE") || subject.contains("DIET PILLS") ||
                subject.contains("CHEAP VIAGRA") || subject.contains("ADULT SINGLES") ||
                subject.contains("MEET GIRLS") || subject.contains("DATING") ||
                subject.contains("CLICK HERE TO CLAIM") || subject.contains("ACT NOW") ||
                fromDomain.endsWith(".xyz") || fromDomain.endsWith(".top") || 
                fromDomain.endsWith(".click") || fromDomain.endsWith(".link") ||
                fromDomain.contains("lottery") || fromDomain.contains("casino") ||
                fromDomain.contains("jackpot") || fromDomain.contains("viagra") ||
                fromDomain.contains("win-cash") || fromDomain.contains("make-money") ||
                fromLocal.contains("lottery") || fromLocal.contains("winner") ||
                fromLocal.contains("jackpot") || fromLocal.contains("bonus") ||
                fromLocal.contains("casino") || fromLocal.contains("viagra") ||
                fromLocal.contains("cialis") || fromLocal.contains("pharmacy") ||
                fromLocal.contains("fast-cash") || fromLocal.contains("easy-money") ||
                fromLocal.contains("crypto-earn") || fromLocal.contains("bitcoin")) {
                return "SPAM";
            }

            // 2. JOB Check
            if (fromDomain.contains("indeed.com") || fromDomain.contains("glassdoor.com") || 
                fromDomain.contains("naukri.com") || fromDomain.contains("monster.com") || 
                fromDomain.contains("ziprecruiter.com") || fromDomain.contains("upwork.com") || 
                fromDomain.contains("fiverr.com") || fromDomain.contains("freelancer.com") ||
                fromDomain.contains("careerbuilder.com") || fromDomain.contains("simplyhired.com") ||
                fromDomain.contains("toptal.com") || fromDomain.contains("workable.com") ||
                fromDomain.contains("greenhouse.io") || fromDomain.contains("lever.co") ||
                fromLocal.contains("careers") || fromLocal.contains("jobs") || 
                fromLocal.contains("hiring") || fromLocal.contains("recruitment") || 
                fromLocal.contains("recruiter") || fromLocal.equals("hr") ||
                fromLocal.contains("talent") || fromLocal.contains("apply") ||
                fromLocal.contains("internship") || fromLocal.contains("employment") ||
                subject.contains("JOB") || subject.contains("HIRING") || 
                subject.contains("VACANCY") || subject.contains("CAREER") || 
                subject.contains("RECRUIT") || subject.contains("RESUME") || 
                subject.contains("INTERVIEW") || subject.contains("APPLICATION") ||
                subject.contains("JOB OFFER") || subject.contains("OFFER LETTER") ||
                subject.contains("EMPLOYMENT") || subject.contains("INTERNSHIP") ||
                subject.contains("JOIN OUR TEAM") || subject.contains("ONBOARDING") ||
                subject.contains("CONTRACTOR") || subject.contains("SALARY") ||
                subject.contains("COMPENSATION") || subject.contains("PORTFOLIO") ||
                subject.contains("SCREENING") || subject.contains("ASSESSMENT")) {
                return "JOB";
            }

            // 3. SOCIAL Check
            if (fromDomain.contains("facebook.com") || fromDomain.contains("instagram.com") || 
                fromDomain.contains("linkedin.com") || fromDomain.contains("twitter.com") || 
                fromDomain.contains("t.co") || fromDomain.contains("tiktok.com") || 
                fromDomain.contains("pinterest.com") || fromDomain.contains("tumblr.com") ||
                fromDomain.contains("reddit.com") || fromDomain.contains("youtube.com") ||
                fromDomain.contains("snapchat.com") || fromDomain.contains("discord.com") ||
                fromDomain.contains("whatsapp.com") || fromDomain.contains("slack.com") ||
                fromDomain.contains("medium.com") || fromDomain.contains("quora.com") ||
                fromDomain.contains("meetup.com") || fromDomain.contains("facebookmail.com") ||
                fromDomain.contains("x.com") ||
                fromLocal.contains("notification") || fromLocal.contains("social") ||
                fromLocal.contains("community") || fromLocal.contains("invite") ||
                fromLocal.contains("friend") || fromLocal.contains("follow") ||
                fromLocal.contains("mention") || fromLocal.contains("comment") ||
                subject.contains("SOCIAL") || subject.contains("FRIEND REQUEST") || 
                subject.contains("NEW FOLLOWER") || subject.contains("MENTIONED YOU") || 
                subject.contains("COMMENTED ON") || subject.contains("INVITATION TO JOIN") || 
                subject.contains("NEW MESSAGE FROM") || subject.contains("REACTION") || 
                subject.contains("RETWEET") || subject.contains("SUBSCRIBED") ||
                subject.contains("CONNECT REQUEST") || subject.contains("ADDED YOU")) {
                return "SOCIAL";
            }

            // 4. PROMOTIONS Check
            if (hasUnsubscribe || isBulk || 
                fromDomain.contains("groupon.com") || fromDomain.contains("coupon.com") ||
                fromDomain.contains("retailmenot.com") || fromDomain.contains("slickdeals.net") ||
                fromDomain.contains("mailchimp.com") || fromDomain.contains("sendgrid.com") ||
                fromDomain.contains("hubspot.com") || fromDomain.contains("constantcontact.com") ||
                fromLocal.contains("newsletter") || fromLocal.contains("marketing") || 
                fromLocal.contains("offers") || fromLocal.contains("sales") ||
                fromLocal.contains("promo") || fromLocal.contains("deals") ||
                fromLocal.contains("coupon") || fromLocal.contains("shop") ||
                fromLocal.contains("store") ||
                subject.contains("SALE") || subject.contains("OFFER") || 
                subject.contains("DISCOUNT") || subject.contains("DEAL") || 
                subject.contains("PROMOTION") || subject.contains("LIMITED TIME") ||
                subject.contains("% OFF") || subject.contains("SAVE") ||
                subject.contains("COUPON") || subject.contains("FREE SHIPPING") ||
                subject.contains("BLACK FRIDAY") || subject.contains("CYBER MONDAY") ||
                subject.contains("BUY ONE GET ONE") || subject.contains("BOGO") ||
                subject.contains("CLEARANCE") || subject.contains("GIFT") ||
                subject.contains("VOUCHER") || subject.contains("BESTSELLERS") ||
                subject.contains("CHECK OUT") || subject.contains("SPECIAL VALUE") ||
                subject.contains("PROMO") || subject.contains("PRICE DROP") ||
                subject.contains("EXCLUSIVE")) {
                return "PROMOTIONS";
            }

            // 5. PURCHASES Check
            if (subject.contains("RECEIPT") || subject.contains("INVOICE") || 
                subject.contains("ORDER") || subject.contains("YOUR ORDER") || 
                subject.contains("PAYMENT") || subject.contains("SHIPPED") ||
                subject.contains("AMAZON") || subject.contains("FLIPKART") ||
                subject.contains("BILL") || subject.contains("DELIVERY") ||
                subject.contains("TRANSACTION") || subject.contains("CONFIRMATION OF ORDER") ||
                subject.contains("PURCHASE") || subject.contains("TRACKING")) {
                return "PURCHASES";
            }

            // 6. UPDATES Check
            if (isAutoSubmitted || 
                fromDomain.contains("github.com") || fromDomain.contains("gitlab.com") ||
                fromDomain.contains("bitbucket.org") || fromDomain.contains("jira.com") ||
                fromDomain.contains("confluence.com") || fromDomain.contains("trello.com") ||
                fromDomain.contains("zoom.us") ||
                fromLocal.equals("no-reply") || fromLocal.equals("noreply") || 
                fromLocal.equals("donotreply") || fromLocal.equals("support") || 
                fromLocal.equals("alerts") || fromLocal.equals("system") ||
                fromLocal.equals("info") || fromLocal.equals("service") ||
                fromLocal.equals("admin") || fromLocal.equals("billing") ||
                subject.contains("OTP") || subject.contains("VERIFICATION") || 
                subject.contains("PASSWORD") || subject.contains("ALERT") || 
                subject.contains("SECURITY") || subject.contains("ACCOUNT") || 
                subject.contains("LOGIN") || subject.contains("UNDELIVERED") || 
                subject.contains("RETURNED TO SENDER") ||
                subject.contains("STATEMENT") || subject.contains("CONFIRMATION") ||
                subject.contains("NOTIFICATION") || subject.contains("UPDATE") ||
                subject.contains("YOUR ACCOUNT") || subject.contains("ACTIVATION") ||
                subject.contains("RENEWAL") || subject.contains("VERIFY YOUR") ||
                from.contains("mailer-daemon") || from.contains("postmaster")) {
                return "UPDATES";
            }

            // 6. DEFAULT: PRIMARY
            // Direct human-to-human correspondence
            return "PRIMARY";

        } catch (Exception e) {
            log.warn("Failed to categorize email: {}", e.getMessage());
            return "PRIMARY";
        }
    }

    private String[] extractContent(Message message) throws MessagingException, IOException {
        String plain = ""; String html = "";
        Object content = message.getContent();
        if (content instanceof String) {
            if (message.isMimeType("text/html")) {
                html = (String) content;
            } else {
                plain = (String) content;
            }
        }
        else if (content instanceof Multipart) {
            Multipart mp = (Multipart) content;
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart bp = mp.getBodyPart(i);
                if (bp.isMimeType("text/plain")) plain = (String) bp.getContent();
                else if (bp.isMimeType("text/html")) html = (String) bp.getContent();
                else if (bp.getContent() instanceof MimeMultipart) {
                    MimeMultipart nested = (MimeMultipart) bp.getContent();
                    for (int j = 0; j < nested.getCount(); j++) {
                        BodyPart nb = nested.getBodyPart(j);
                        if (nb.isMimeType("text/plain")) plain = (String) nb.getContent();
                        else if (nb.isMimeType("text/html")) html = (String) nb.getContent();
                    }
                }
            }
        }
        if (plain.isEmpty() && !html.isEmpty()) plain = html.replaceAll("<[^>]*>", "").trim();
        return new String[]{plain, html};
    }

    private boolean hasAttachments(Part part) throws MessagingException, IOException {
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                if (hasAttachments(mp.getBodyPart(i))) return true;
            }
        } else {
            String disp = part.getDisposition();
            if (Part.ATTACHMENT.equalsIgnoreCase(disp) || Part.INLINE.equalsIgnoreCase(disp)) {
                if (part.getFileName() != null) return true;
            } else if (part.isMimeType("message/rfc822") || part.isMimeType("message/delivery-status") || part.isMimeType("text/rfc822-headers")) {
                return true;
            }
        }
        return false;
    }

    private List<String> extractAttachments(Part part) throws MessagingException, IOException {
        List<String> names = new ArrayList<>();
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) names.addAll(extractAttachments(mp.getBodyPart(i)));
        } else {
            String disp = part.getDisposition();
            String name = part.getFileName();
            if (Part.ATTACHMENT.equalsIgnoreCase(disp) || Part.INLINE.equalsIgnoreCase(disp)) {
                if (name != null) names.add(jakarta.mail.internet.MimeUtility.decodeText(name));
            } else if (part.isMimeType("message/rfc822")) {
                names.add(name != null ? jakarta.mail.internet.MimeUtility.decodeText(name) : "original_message.eml");
            } else if (part.isMimeType("message/delivery-status") || part.isMimeType("text/rfc822-headers")) {
                names.add(name != null ? jakarta.mail.internet.MimeUtility.decodeText(name) : "delivery_status.txt");
            }
        }
        return names;
    }

    public Store connect(String email, String password) throws MessagingException {
        Properties props = new Properties();
        props.put("mail.imap.host", imapHost);
        props.put("mail.imap.port", String.valueOf(imapPort));
        props.put("mail.imap.ssl.enable", String.valueOf(imapSslEnable));
        props.put("mail.debug", "true"); // CRITICAL: Enable debug logs to see IMAP traffic
        if (imapSslEnable) {
            props.put("mail.imap.ssl.trust", "*");
        }
        // Set timeouts to prevent hanging threads
        props.put("mail.imap.connectiontimeout", "10000");
        props.put("mail.imap.timeout", "10000");

        Session session = Session.getInstance(props);
        Store store = session.getStore(imapProtocol);
        store.connect(imapHost, imapPort, email, password);
        return store;
    }

    private void cleanup(Store store, Folder folder) {
        try { if (folder != null && folder.isOpen()) folder.close(false); } catch (Exception e) {}
        try { if (store != null) store.close(); } catch (Exception e) {}
    }

    public void downloadAttachment(String email, String password, String folderName, String uid, String fileName, java.io.OutputStream os) {
        Store store = null; Folder folder = null;
        try {
            store = connect(email, password);
            String actualFolderName = folderName;
            if ("Sent".equalsIgnoreCase(folderName)) {
                actualFolderName = resolveSentFolderName(store);
            } else if ("Trash".equalsIgnoreCase(folderName)) {
                actualFolderName = resolveTrashFolderName(store);
            } else if ("Spam".equalsIgnoreCase(folderName)) {
                actualFolderName = resolveSpamFolderName(store);
            } else if ("Snoozed".equalsIgnoreCase(folderName)) {
                actualFolderName = resolveSnoozedFolderName(store);
            } else if ("Archive".equalsIgnoreCase(folderName)) {
                actualFolderName = resolveArchiveFolderName(store);
            } else if ("Drafts".equalsIgnoreCase(folderName) || "Draft".equalsIgnoreCase(folderName)) {
                actualFolderName = resolveDraftsFolderName(store);
            } else if (folderName == null || folderName.isEmpty()) {
                actualFolderName = "INBOX";
            }

            folder = store.getFolder(actualFolderName);
            folder.open(Folder.READ_ONLY);

            if (!(folder instanceof UIDFolder)) {
                 throw new MailException(actualFolderName + " does not support persistent UIDs.");
            }
            UIDFolder uidFolder = (UIDFolder) folder;

            Message message = uidFolder.getMessageByUID(Long.parseLong(uid));
            if (message == null) throw new MailException("Email with UID " + uid + " not found");

            if (message.getContent() instanceof Multipart) findAndWriteAttachment((Multipart) message.getContent(), fileName, os);
            else throw new MailException("No attachments found");
        } catch (Exception e) {
            log.error("Download failed for UID {}: {}", uid, e.getMessage());
            throw new MailException("Download failed: " + e.getMessage());
        } finally {
            cleanup(store, folder);
        }
    }

    private boolean findAndWriteAttachment(Multipart mp, String name, java.io.OutputStream os) throws MessagingException, IOException {
        for (int i = 0; i < mp.getCount(); i++) {
            BodyPart bp = mp.getBodyPart(i);
            String fn = bp.getFileName();
            String decodedFn = fn != null ? jakarta.mail.internet.MimeUtility.decodeText(fn) : null;
            
            if (decodedFn == null) {
                if (bp.isMimeType("message/rfc822")) decodedFn = "original_message.eml";
                else if (bp.isMimeType("message/delivery-status") || bp.isMimeType("text/rfc822-headers")) decodedFn = "delivery_status.txt";
            }
            
            if (decodedFn != null && decodedFn.equalsIgnoreCase(name)) {
                bp.getDataHandler().getInputStream().transferTo(os);
                return true;
            }
            if (bp.isMimeType("multipart/*")) {
                if (findAndWriteAttachment((Multipart) bp.getContent(), name, os)) return true;
            }
        }
        return false;
    }

    @Transactional
    public void unsubscribeSender(String userEmail, String senderEmail) {
        log.info("Unsubscribing/blocking sender {} for user {}", senderEmail, userEmail);
        String cleanEmail = extractEmailAddress(senderEmail);
        if (cleanEmail == null || cleanEmail.isEmpty()) {
            throw new IllegalArgumentException("Invalid sender email address");
        }
        cleanEmail = cleanEmail.toLowerCase();
        if (!blockedContactRepository.existsByUserEmailAndBlockedEmail(userEmail, cleanEmail)) {
            BlockedContact blocked = BlockedContact.builder()
                    .userEmail(userEmail)
                    .blockedEmail(cleanEmail)
                    .blockedAt(LocalDateTime.now())
                    .build();
            blockedContactRepository.save(blocked);
        }
    }

    @Transactional
    public void subscribeSender(String userEmail, String senderEmail) {
        log.info("Subscribing/unblocking sender {} for user {}", senderEmail, userEmail);
        String cleanEmail = extractEmailAddress(senderEmail);
        if (cleanEmail == null || cleanEmail.isEmpty()) {
            throw new IllegalArgumentException("Invalid sender email address");
        }
        cleanEmail = cleanEmail.toLowerCase();
        blockedContactRepository.deleteByUserEmailAndBlockedEmail(userEmail, cleanEmail);
    }

    public List<String> getBlockedSenders(String userEmail) {
        log.info("Fetching blocked senders for user {}", userEmail);
        return blockedContactRepository.findByUserEmail(userEmail).stream()
                .map(BlockedContact::getBlockedEmail)
                .map(String::toLowerCase)
                .toList();
    }

    public com.btctech.mailapp.dto.AnalyticsDTO getAnalytics(String email, String password, String timezoneId) {
        log.info("Fetching analytics for: {} with timezone: {}", email, timezoneId);
        Store store = null;
        com.btctech.mailapp.dto.AnalyticsDTO analytics = new com.btctech.mailapp.dto.AnalyticsDTO();
        
        java.util.Map<String, Integer> folderCounts = new java.util.HashMap<>();
        java.util.Map<String, Integer> receivedByDate = new java.util.HashMap<>();
        java.util.Map<String, Integer> sentByDate = new java.util.HashMap<>();
        java.util.Map<String, Integer> receivedByMonth = new java.util.HashMap<>();
        java.util.Map<String, Integer> sentByMonth = new java.util.HashMap<>();
        java.util.Map<String, Integer> topSenders = new java.util.HashMap<>();
        java.util.Map<String, Integer> topReceivers = new java.util.HashMap<>();

        try {
            store = connect(email, password);
            String[] commonFolders = {"INBOX", resolveSentFolderName(store), resolveDraftsFolderName(store), resolveSpamFolderName(store), resolveTrashFolderName(store), resolveArchiveFolderName(store)};
            String[] folderLabels = {"INBOX", "Sent", "Drafts", "Spam", "Trash", "Archive"};

            for (int i = 0; i < commonFolders.length; i++) {
                Folder f = null;
                try {
                    f = store.getFolder(commonFolders[i]);
                    if (f.exists()) {
                        folderCounts.put(folderLabels[i], f.getMessageCount());
                        
                        // Process timeline/senders for Inbox and Sent (limit to last 1000 for performance)
                        if (folderLabels[i].equals("INBOX") || folderLabels[i].equals("Sent")) {
                            f.open(Folder.READ_ONLY);
                            int count = f.getMessageCount();
                            int start = Math.max(1, count - 1000 + 1);
                            if (count > 0) {
                                Message[] msgs = f.getMessages(start, count);
                                FetchProfile fp = new FetchProfile();
                                fp.add(FetchProfile.Item.ENVELOPE);
                                f.fetch(msgs, fp);

                                java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd");
                                java.text.SimpleDateFormat monthFormat = new java.text.SimpleDateFormat("yyyy-MM");
                                
                                if (timezoneId != null && !timezoneId.isEmpty()) {
                                    try {
                                        java.util.TimeZone tz = java.util.TimeZone.getTimeZone(timezoneId);
                                        dateFormat.setTimeZone(tz);
                                        monthFormat.setTimeZone(tz);
                                    } catch (Exception tzEx) {
                                        log.warn("Invalid timezone: {}, defaulting to system timezone", timezoneId);
                                    }
                                }

                                for (Message msg : msgs) {
                                    try {
                                        java.util.Date receivedDate = msg.getReceivedDate();
                                        if (receivedDate == null) receivedDate = msg.getSentDate();
                                        if (receivedDate == null) receivedDate = new java.util.Date(); // Robust fallback so message isn't skipped
                                        
                                        if (receivedDate != null) {
                                            String dStr = dateFormat.format(receivedDate);
                                            String mStr = monthFormat.format(receivedDate);

                                            if (folderLabels[i].equals("INBOX")) {
                                                receivedByDate.put(dStr, receivedByDate.getOrDefault(dStr, 0) + 1);
                                                receivedByMonth.put(mStr, receivedByMonth.getOrDefault(mStr, 0) + 1);
                                                
                                                Address[] froms = msg.getFrom();
                                                if (froms != null && froms.length > 0) {
                                                    String fromEmail = extractEmailAddress(froms[0].toString());
                                                    if (fromEmail != null) {
                                                        topSenders.put(fromEmail, topSenders.getOrDefault(fromEmail, 0) + 1);
                                                    }
                                                }
                                            } else {
                                                sentByDate.put(dStr, sentByDate.getOrDefault(dStr, 0) + 1);
                                                sentByMonth.put(mStr, sentByMonth.getOrDefault(mStr, 0) + 1);
                                                
                                                Address[] tos = msg.getRecipients(Message.RecipientType.TO);
                                                if (tos != null && tos.length > 0) {
                                                    for (Address to : tos) {
                                                        String toEmail = extractEmailAddress(to.toString());
                                                        if (toEmail != null) {
                                                            topReceivers.put(toEmail, topReceivers.getOrDefault(toEmail, 0) + 1);
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } catch (Exception e) {
                                        // Ignore individual message parse errors
                                    }
                                }
                            }
                            f.close(false);
                        }
                    }
                } catch (Exception ex) {
                    log.warn("Analytics: failed to process folder {}", commonFolders[i], ex);
                }
            }

            analytics.setFolderCounts(folderCounts);
            analytics.setReceivedByDate(receivedByDate);
            analytics.setSentByDate(sentByDate);
            analytics.setReceivedByMonth(receivedByMonth);
            analytics.setSentByMonth(sentByMonth);
            analytics.setTopSenders(topSenders);
            analytics.setTopReceivers(topReceivers);

        } catch (Exception e) {
            log.error("Failed to fetch analytics for {}: {}", email, e.getMessage(), e);
            throw new MailException("Failed to fetch analytics: " + e.getMessage());
        } finally {
            cleanup(store, null);
        }
        
        return analytics;
    }
}