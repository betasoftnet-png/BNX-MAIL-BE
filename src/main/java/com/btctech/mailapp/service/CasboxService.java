package com.btctech.mailapp.service;

import com.btctech.mailapp.dto.CasboxMessageDto;
import com.btctech.mailapp.dto.CasboxSendRequest;
import com.btctech.mailapp.entity.CasboxMessage;
import com.btctech.mailapp.repository.CasboxMessageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.entity.UserSettings;
import com.btctech.mailapp.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CasboxService {

    private final CasboxMessageRepository casboxMessageRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ContactAliasService contactAliasService;
    private final ConnectionService connectionService;
    private final UserService userService;
    private final UserRepository userRepository;

    @Autowired
    public CasboxService(CasboxMessageRepository casboxMessageRepository,
                         SimpMessagingTemplate messagingTemplate,
                         ContactAliasService contactAliasService,
                         @org.springframework.context.annotation.Lazy ConnectionService connectionService,
                         @org.springframework.context.annotation.Lazy UserService userService,
                         UserRepository userRepository) {
        this.casboxMessageRepository = casboxMessageRepository;
        this.messagingTemplate = messagingTemplate;
        this.contactAliasService = contactAliasService;
        this.connectionService = connectionService;
        this.userService = userService;
        this.userRepository = userRepository;
    }

    public CasboxService(CasboxMessageRepository casboxMessageRepository,
                         SimpMessagingTemplate messagingTemplate,
                         ContactAliasService contactAliasService,
                         ConnectionService connectionService) {
        this(casboxMessageRepository, messagingTemplate, contactAliasService, connectionService, null, null);
    }

    public CasboxService(CasboxMessageRepository casboxMessageRepository,
                         SimpMessagingTemplate messagingTemplate,
                         ContactAliasService contactAliasService) {
        this(casboxMessageRepository, messagingTemplate, contactAliasService, null, null, null);
    }

    // Backwards-compatible constructor for testing and mock setups
    public CasboxService(CasboxMessageRepository casboxMessageRepository,
                         SimpMessagingTemplate messagingTemplate) {
        this(casboxMessageRepository, messagingTemplate, null, null, null, null);
    }

    @Transactional
    public CasboxMessageDto sendMessage(String senderEmail, CasboxSendRequest request) {
        if (connectionService != null) {
            com.btctech.mailapp.entity.User sender = connectionService.resolveUser(senderEmail);
            com.btctech.mailapp.entity.User receiver = connectionService.resolveUser(request.getReceiverEmail());
            if (sender != null && receiver != null) {
                if (!connectionService.isConnectionActive(sender.getId(), receiver.getId())) {
                    throw new com.btctech.mailapp.exception.MailException("Cannot send message. This connection is disconnected.");
                }
            }
        }

        CasboxMessage message = new CasboxMessage();
        message.setSenderEmail(senderEmail);
        message.setReceiverEmail(request.getReceiverEmail());
        message.setSubject(request.getSubject());
        message.setBody(request.getBody());
        message.setAttachmentsJson(request.getAttachmentsJson());
        message.setStatus("SENT");
        message.setTimestamp(LocalDateTime.now());

        CasboxMessage saved = casboxMessageRepository.save(message);

        Map<String, String> senderAliasMap = getAliasLookupMap(senderEmail);
        Map<String, String> receiverAliasMap = getAliasLookupMap(request.getReceiverEmail());

        CasboxMessageDto senderDto = convertToDto(saved, senderEmail, senderAliasMap);
        CasboxMessageDto receiverDto = convertToDto(saved, request.getReceiverEmail(), receiverAliasMap);

        // Send to receiver via WebSocket (private to receiver's view)
        if (messagingTemplate != null) {
            messagingTemplate.convertAndSendToUser(
                    request.getReceiverEmail(),
                    "/queue/casbox/messages",
                    receiverDto
            );

            // Also send to sender via WebSocket for instant UI update (private to sender's view)
            messagingTemplate.convertAndSendToUser(
                    senderEmail,
                    "/queue/casbox/messages",
                    senderDto
            );
        }

        return senderDto;
    }

    @Transactional(readOnly = true)
    public List<CasboxMessageDto> getThread(String email1, String email2) {
        Map<String, String> aliasMap = getAliasLookupMap(email1);
        return casboxMessageRepository.findConversation(email1, email2)
                .stream()
                .map(m -> convertToDto(m, email1, aliasMap))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<CasboxMessageDto> getAllMessages(String userEmail) {
        Map<String, String> aliasMap = getAliasLookupMap(userEmail);
        java.util.Set<String> disconnectedIdentifiers = Collections.emptySet();
        if (connectionService != null && userEmail != null) {
            try {
                com.btctech.mailapp.entity.User user = connectionService.resolveUser(userEmail);
                if (user != null) {
                    disconnectedIdentifiers = connectionService.getDisconnectedContactIdentifiers(user.getId());
                }
            } catch (Exception ignored) {}
        }

        final java.util.Set<String> disconnected = disconnectedIdentifiers;
        return casboxMessageRepository.findAllMessagesForUser(userEmail)
                .stream()
                .filter(m -> {
                    if (disconnected == null || disconnected.isEmpty()) return true;
                    String otherEmail = userEmail != null && userEmail.equalsIgnoreCase(m.getSenderEmail())
                            ? m.getReceiverEmail() : m.getSenderEmail();
                    if (otherEmail != null) {
                        if (disconnected.contains(otherEmail.toLowerCase())) {
                            return false;
                        }
                        String otherUser = extractUsername(otherEmail);
                        if (otherUser != null && disconnected.contains(otherUser.toLowerCase())) {
                            return false;
                        }
                    }
                    return true;
                })
                .map(m -> convertToDto(m, userEmail, aliasMap))
                .collect(Collectors.toList());
    }

    @Transactional
    public void updateArchiveStatus(List<Long> messageIds, Boolean archived, String userEmail) {
        if (messageIds == null || messageIds.isEmpty()) return;
        List<CasboxMessage> messages = casboxMessageRepository.findAllById(messageIds);
        boolean state = Boolean.TRUE.equals(archived);
        for (CasboxMessage msg : messages) {
            if (msg.getSenderEmail() != null && msg.getSenderEmail().equalsIgnoreCase(userEmail)) {
                msg.setSenderArchived(state);
            }
            if (msg.getReceiverEmail() != null && msg.getReceiverEmail().equalsIgnoreCase(userEmail)) {
                msg.setReceiverArchived(state);
            }
        }
        casboxMessageRepository.saveAll(messages);
    }

    @Transactional
    public void updateStatus(List<Long> messageIds, String status, String receiverEmail) {
        List<CasboxMessage> messages = casboxMessageRepository.findAllById(messageIds);
        for (CasboxMessage msg : messages) {
            // Only update if the current user is the receiver of these messages
            if (msg.getReceiverEmail().equals(receiverEmail)) {
                msg.setStatus(status);
                
                // Notify sender about the status update
                if (messagingTemplate != null) {
                    messagingTemplate.convertAndSendToUser(
                            msg.getSenderEmail(),
                            "/queue/casbox/status",
                            convertToDto(msg, msg.getSenderEmail())
                    );
                }
            }
        }
        casboxMessageRepository.saveAll(messages);
    }
    
    @Transactional
    public void markUnseenAsDelivered(String receiverEmail) {
        List<CasboxMessage> unseen = casboxMessageRepository.findUnseenMessagesForUser(receiverEmail);
        for (CasboxMessage msg : unseen) {
            if ("SENT".equals(msg.getStatus())) {
                msg.setStatus("DELIVERED");
                if (messagingTemplate != null) {
                    messagingTemplate.convertAndSendToUser(
                            msg.getSenderEmail(),
                            "/queue/casbox/status",
                            convertToDto(msg, msg.getSenderEmail())
                    );
                }
            }
        }
        casboxMessageRepository.saveAll(unseen);
    }

    private Map<String, String> getAliasLookupMap(String userEmail) {
        if (contactAliasService != null && userEmail != null) {
            try {
                return contactAliasService.getAliasLookupMap(userEmail);
            } catch (Exception e) {
                // Ignore and return empty map
            }
        }
        return Collections.emptyMap();
    }

    private CasboxMessageDto convertToDto(CasboxMessage entity) {
        return convertToDto(entity, null, Collections.emptyMap());
    }

    private CasboxMessageDto convertToDto(CasboxMessage entity, String currentUserEmail) {
        Map<String, String> aliasMap = getAliasLookupMap(currentUserEmail);
        return convertToDto(entity, currentUserEmail, aliasMap);
    }

    private CasboxMessageDto convertToDto(CasboxMessage entity, String currentUserEmail, Map<String, String> aliasMap) {
        CasboxMessageDto dto = new CasboxMessageDto();
        dto.setId(entity.getId());
        dto.setSenderEmail(entity.getSenderEmail());
        dto.setReceiverEmail(entity.getReceiverEmail());
        dto.setSubject(entity.getSubject());
        dto.setBody(entity.getBody());
        dto.setAttachmentsJson(entity.getAttachmentsJson());
        dto.setStatus(entity.getStatus());
        dto.setTimestamp(entity.getTimestamp());
        dto.setSenderArchived(entity.isSenderArchived());
        dto.setReceiverArchived(entity.isReceiverArchived());

        boolean archived = false;
        if (currentUserEmail != null) {
            if (currentUserEmail.equalsIgnoreCase(entity.getSenderEmail()) && currentUserEmail.equalsIgnoreCase(entity.getReceiverEmail())) {
                archived = entity.isSenderArchived() || entity.isReceiverArchived();
            } else if (currentUserEmail.equalsIgnoreCase(entity.getSenderEmail())) {
                archived = entity.isSenderArchived();
            } else if (currentUserEmail.equalsIgnoreCase(entity.getReceiverEmail())) {
                archived = entity.isReceiverArchived();
            }
        }
        dto.setIsArchived(archived);

        // Derive usernames
        String senderUsername = extractUsername(entity.getSenderEmail());
        String receiverUsername = extractUsername(entity.getReceiverEmail());
        dto.setSenderUsername(senderUsername);
        dto.setReceiverUsername(receiverUsername);

        // Determine contact identifier relative to currentUserEmail
        String contactEmail = null;
        String contactUsername = null;
        if (currentUserEmail != null) {
            if (currentUserEmail.equalsIgnoreCase(entity.getSenderEmail())) {
                contactEmail = entity.getReceiverEmail();
                contactUsername = receiverUsername;
            } else {
                contactEmail = entity.getSenderEmail();
                contactUsername = senderUsername;
            }
        } else {
            contactEmail = entity.getSenderEmail();
            contactUsername = senderUsername;
        }
        dto.setContactUsername(contactUsername);

        // Alias resolution
        String customName = null;
        if (contactEmail != null && aliasMap != null && !aliasMap.isEmpty()) {
            if (aliasMap.containsKey(contactEmail.toLowerCase())) {
                customName = aliasMap.get(contactEmail.toLowerCase());
            } else if (contactUsername != null && aliasMap.containsKey(contactUsername.toLowerCase())) {
                customName = aliasMap.get(contactUsername.toLowerCase());
            }
        }
        dto.setCustomName(customName);
        dto.setContactDisplayName(customName != null ? customName : (contactUsername != null ? contactUsername : contactEmail));

        // Sender & receiver display names
        String senderAlias = null;
        String receiverAlias = null;
        if (aliasMap != null && !aliasMap.isEmpty()) {
            if (entity.getSenderEmail() != null && aliasMap.containsKey(entity.getSenderEmail().toLowerCase())) {
                senderAlias = aliasMap.get(entity.getSenderEmail().toLowerCase());
            } else if (senderUsername != null && aliasMap.containsKey(senderUsername.toLowerCase())) {
                senderAlias = aliasMap.get(senderUsername.toLowerCase());
            }

            if (entity.getReceiverEmail() != null && aliasMap.containsKey(entity.getReceiverEmail().toLowerCase())) {
                receiverAlias = aliasMap.get(entity.getReceiverEmail().toLowerCase());
            } else if (receiverUsername != null && aliasMap.containsKey(receiverUsername.toLowerCase())) {
                receiverAlias = aliasMap.get(receiverUsername.toLowerCase());
            }
        }
        dto.setSenderDisplayName(senderAlias != null ? senderAlias : senderUsername);
        dto.setReceiverDisplayName(receiverAlias != null ? receiverAlias : receiverUsername);

        // Determine if message / contact is accepted for currentUserEmail
        boolean accepted = false;
        if (currentUserEmail != null && !currentUserEmail.trim().isEmpty()) {
            String normCurrent = currentUserEmail.trim().toLowerCase();
            String normSender = entity.getSenderEmail() != null ? entity.getSenderEmail().trim().toLowerCase() : "";
            String currentLocal = normCurrent.contains("@") ? normCurrent.substring(0, normCurrent.indexOf("@")) : normCurrent;
            String senderLocal = normSender.contains("@") ? normSender.substring(0, normSender.indexOf("@")) : normSender;

            boolean isCurrentUserSender = normCurrent.equals(normSender) || currentLocal.equals(senderLocal);
            if (isCurrentUserSender) {
                // Outgoing messages sent by current user are always in Messages
                accepted = true;
            } else {
                // Incoming message: evaluate sender's current accepted status relative to current user
                accepted = isContactAccepted(normCurrent, normSender);
            }
        }
        dto.setIsAccepted(accepted);

        return dto;
    }

    private String extractUsername(String email) {
        if (email == null) return "";
        if (email.contains("@")) {
            return email.substring(0, email.indexOf("@"));
        }
        return email;
    }

    public boolean isContactAccepted(String currentUserEmail, String contactEmail) {
        if (currentUserEmail == null || contactEmail == null) {
            return false;
        }

        String normUser = currentUserEmail.trim().toLowerCase();
        String normContact = contactEmail.trim().toLowerCase();

        if (normContact.isEmpty() || normUser.isEmpty()) {
            return false;
        }

        if (normUser.equals(normContact)) {
            return true;
        }

        String contactLocal = normContact.contains("@") ? normContact.substring(0, normContact.indexOf("@")) : normContact;
        String userLocal = normUser.contains("@") ? normUser.substring(0, normUser.indexOf("@")) : normUser;

        if (contactLocal.equalsIgnoreCase(userLocal)) {
            return true;
        }

        // 1. Check UserSettings.casboxAccepted for currentUser
        if (userService != null) {
            try {
                User user = resolveUser(normUser);
                if (user != null) {
                    UserSettings settings = userService.getSettings(user);
                    if (settings != null && settings.getCasboxAccepted() != null) {
                        for (String accepted : settings.getCasboxAccepted()) {
                            if (accepted == null) continue;
                            String normAccepted = accepted.trim().toLowerCase();
                            String acceptedLocal = normAccepted.contains("@") ? normAccepted.substring(0, normAccepted.indexOf("@")) : normAccepted;
                            if (normAccepted.equals(normContact) || acceptedLocal.equals(contactLocal) || normAccepted.equals(contactLocal)) {
                                return true;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // 2. Check ConnectionService active connections
        if (connectionService != null) {
            try {
                User user = resolveUser(normUser);
                User contact = resolveUser(normContact);
                if (user != null && contact != null) {
                    Set<String> disconnected = connectionService.getDisconnectedContactIdentifiers(user.getId());
                    if (disconnected.contains(String.valueOf(contact.getId()))
                            || (contact.getUsername() != null && disconnected.contains(contact.getUsername().toLowerCase()))
                            || (contact.getEmail() != null && disconnected.contains(contact.getEmail().toLowerCase()))) {
                        return false;
                    }

                    if (connectionService.isConnectionActive(user.getId(), contact.getId())) {
                        if (connectionService.isConnectionAccepted(user.getId(), contact.getId())) {
                            return true;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        return false;
    }

    private User resolveUser(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) return null;
        if (connectionService != null) {
            try {
                User u = connectionService.resolveUser(identifier);
                if (u != null) return u;
            } catch (Exception ignored) {}
        }
        if (userRepository != null) {
            try {
                String clean = identifier.trim().toLowerCase();
                Optional<User> byEmail = userRepository.findByEmail(clean);
                if (byEmail.isPresent()) return byEmail.get();
                Optional<User> byUser = userRepository.findByUsername(clean);
                if (byUser.isPresent()) return byUser.get();
                if (clean.contains("@")) {
                    String local = clean.substring(0, clean.indexOf("@"));
                    Optional<User> byLocal = userRepository.findByUsername(local);
                    if (byLocal.isPresent()) return byLocal.get();
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    @Transactional
    public void deleteConversation(String userEmail, String contactEmailOrId) {
        if (userEmail == null || contactEmailOrId == null || contactEmailOrId.trim().isEmpty()) {
            return;
        }

        String contactEmail = contactEmailOrId.trim();

        // Support passing either a numeric message ID or contact email
        if (contactEmail.matches("\\d+")) {
            try {
                Long id = Long.parseLong(contactEmail);
                CasboxMessage msg = casboxMessageRepository.findById(id).orElse(null);
                if (msg != null) {
                    if (userEmail.equalsIgnoreCase(msg.getSenderEmail())) {
                        contactEmail = msg.getReceiverEmail();
                    } else if (userEmail.equalsIgnoreCase(msg.getReceiverEmail())) {
                        contactEmail = msg.getSenderEmail();
                    } else {
                        throw new org.springframework.security.access.AccessDeniedException("Unauthorized to delete this conversation");
                    }
                } else {
                    return;
                }
            } catch (NumberFormatException ignored) {}
        }

        casboxMessageRepository.deleteConversation(userEmail.trim(), contactEmail);
    }
}
