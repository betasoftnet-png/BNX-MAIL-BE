package com.btctech.mailapp.service;

import com.btctech.mailapp.dto.CasboxMessageDto;
import com.btctech.mailapp.dto.CasboxSendRequest;
import com.btctech.mailapp.entity.CasboxMessage;
import com.btctech.mailapp.repository.CasboxMessageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class CasboxService {

    private final CasboxMessageRepository casboxMessageRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ContactAliasService contactAliasService;
    private final ConnectionService connectionService;

    @Autowired
    public CasboxService(CasboxMessageRepository casboxMessageRepository,
                         SimpMessagingTemplate messagingTemplate,
                         ContactAliasService contactAliasService,
                         @org.springframework.context.annotation.Lazy ConnectionService connectionService) {
        this.casboxMessageRepository = casboxMessageRepository;
        this.messagingTemplate = messagingTemplate;
        this.contactAliasService = contactAliasService;
        this.connectionService = connectionService;
    }

    public CasboxService(CasboxMessageRepository casboxMessageRepository,
                         SimpMessagingTemplate messagingTemplate,
                         ContactAliasService contactAliasService) {
        this(casboxMessageRepository, messagingTemplate, contactAliasService, null);
    }

    // Backwards-compatible constructor for testing and mock setups
    public CasboxService(CasboxMessageRepository casboxMessageRepository,
                         SimpMessagingTemplate messagingTemplate) {
        this(casboxMessageRepository, messagingTemplate, null, null);
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

        return dto;
    }

    private String extractUsername(String email) {
        if (email == null) return "";
        if (email.contains("@")) {
            return email.substring(0, email.indexOf("@"));
        }
        return email;
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
