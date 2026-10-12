package com.btctech.mailapp.controller;

import com.btctech.mailapp.dto.ChatDTO;
import com.btctech.mailapp.entity.Chat;
import com.btctech.mailapp.entity.ChatMessage;
import com.btctech.mailapp.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {
    private final ChatService chatService;
    private final com.btctech.mailapp.repository.ChatMessageRepository chatMessageRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @PostMapping("/direct")
    public ResponseEntity<ChatDTO> createDirectChat(@RequestBody ChatDTO.CreateDirect request) {
        Chat chat = chatService.createDirectChat(request.getUser1(), request.getUser2());
        return ResponseEntity.ok(convertToDTO(chat));
    }

    @PostMapping("/group")
    public ResponseEntity<ChatDTO> createGroupChat(
            @RequestBody ChatDTO.CreateGroup request,
            org.springframework.security.core.Authentication authentication) {
        String creatorEmail = authentication.getName();
        Chat chat = chatService.createGroupChat(request.getName(), request.getMembers(), creatorEmail);
        return ResponseEntity.ok(convertToDTO(chat));
    }

    @GetMapping("/user/{email}")
    public ResponseEntity<List<ChatDTO>> getUserChats(@PathVariable String email) {
        List<Chat> chats = chatService.getUserChats(email);
        return ResponseEntity.ok(chats.stream().map(this::convertToDTO).collect(Collectors.toList()));
    }

    @GetMapping("/{chatId}/messages")
    public ResponseEntity<List<ChatDTO.MessageResponse>> getChatMessages(@PathVariable Long chatId) {
        List<ChatMessage> messages = chatService.getChatMessages(chatId);
        return ResponseEntity.ok(messages.stream().map(this::convertToMessageResponse).collect(Collectors.toList()));
    }

    @PostMapping("/message")
    public ResponseEntity<ChatDTO.MessageResponse> sendMessageRest(@RequestBody ChatDTO.MessageRequest request) {
        ChatMessage saved = chatService.saveMessage(request.getChatId(), request.getSender(), request.getMessage(), request.getAttachmentsJson());
        ChatDTO.MessageResponse response = convertToMessageResponse(saved);
        
        // Even if sent via REST, we broadcast to WebSocket for real-time
        messagingTemplate.convertAndSend("/topic/chat/" + request.getChatId(), response);
        
        return ResponseEntity.ok(response);
    }

    @MessageMapping("/chat.send")
    public void sendMessage(@Payload ChatDTO.MessageRequest request) {
        ChatMessage saved = chatService.saveMessage(request.getChatId(), request.getSender(), request.getMessage(), request.getAttachmentsJson());
        ChatDTO.MessageResponse response = convertToMessageResponse(saved);
        
        // Broadcast to the chat topic
        messagingTemplate.convertAndSend("/topic/chat/" + request.getChatId(), response);
    }

    private ChatDTO convertToDTO(Chat chat) {
        ChatDTO dto = new ChatDTO();
        dto.setId(chat.getId());
        dto.setName(chat.getName());
        dto.setType(chat.getType());
        dto.setMemberEmails(chat.getMembers().stream()
                .map(u -> u.getEmail() != null ? u.getEmail() : u.getUsername())
                .collect(Collectors.toList()));
        
        if (chat.getCreator() != null) {
            dto.setCreatorEmail(chat.getCreator().getEmail() != null ? chat.getCreator().getEmail() : chat.getCreator().getUsername());
        }

        Set<String> adminEmails = new HashSet<>();
        if (chat.getCreator() != null) {
            String cEmail = chat.getCreator().getEmail() != null ? chat.getCreator().getEmail() : chat.getCreator().getUsername();
            if (cEmail != null) adminEmails.add(cEmail);
        }
        if (chat.getAdmins() != null) {
            for (com.btctech.mailapp.entity.User u : chat.getAdmins()) {
                String aEmail = u.getEmail() != null ? u.getEmail() : u.getUsername();
                if (aEmail != null) adminEmails.add(aEmail);
            }
        }
        dto.setAdminEmails(new java.util.ArrayList<>(adminEmails));
        
        // Populate last message info efficiently
        chatMessageRepository.findFirstByChatOrderByTimestampDesc(chat).ifPresentOrElse(last -> {
            dto.setLastMessage(last.getContent());
            dto.setLastMessageTime(last.getTimestamp().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        }, () -> {
            dto.setLastMessage("");
            dto.setLastMessageTime("");
        });
        dto.setUnreadCount(0);
        
        return dto;
    }

    private ChatDTO.MessageResponse convertToMessageResponse(ChatMessage message) {
        ChatDTO.MessageResponse response = new ChatDTO.MessageResponse();
        response.setId(message.getId());
        response.setChatId(message.getChat().getId());
        response.setSender(message.getSenderEmail());
        response.setContent(message.getContent());
        response.setTimestamp(message.getTimestamp().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        response.setAttachmentsJson(message.getAttachmentsJson());
        return response;
    }

    @PostMapping("/{chatId}/members")
    public ResponseEntity<ChatDTO> addMembers(
            @PathVariable Long chatId,
            @RequestBody ChatDTO.AddMembers request,
            org.springframework.security.core.Authentication authentication) {
        String inviterEmail = authentication.getName();
        Chat updated = chatService.inviteMembersToGroup(chatId, request.getEmails(), inviterEmail);
        return ResponseEntity.ok(convertToDTO(updated));
    }

    @GetMapping("/invitations")
    public ResponseEntity<List<ChatDTO.InvitationResponse>> getInvitations(
            org.springframework.security.core.Authentication authentication) {
        String userEmail = authentication.getName();
        List<com.btctech.mailapp.entity.ChatInvitation> invitations = chatService.getPendingInvitations(userEmail);
        List<ChatDTO.InvitationResponse> response = invitations.stream().map(inv -> {
            ChatDTO.InvitationResponse res = new ChatDTO.InvitationResponse();
            res.setId(inv.getId());
            res.setChatId(inv.getChat().getId());
            res.setChatName(inv.getChat().getName());
            res.setChatType(inv.getChat().getType());
            if (inv.getInviter() != null) {
                res.setInviterEmail(inv.getInviter().getEmail() != null ? inv.getInviter().getEmail() : inv.getInviter().getUsername());
            } else {
                res.setInviterEmail(null);
            }
            res.setStatus(inv.getStatus().name());
            res.setCreatedAt(inv.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            return res;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/invitations/{id}/accept")
    public ResponseEntity<Void> acceptInvitation(
            @PathVariable Long id,
            org.springframework.security.core.Authentication authentication) {
        chatService.acceptInvitation(id, authentication.getName());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/invitations/{id}/reject")
    public ResponseEntity<Void> rejectInvitation(
            @PathVariable Long id,
            org.springframework.security.core.Authentication authentication) {
        chatService.rejectInvitation(id, authentication.getName());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{chatId}/members")
    public ResponseEntity<List<String>> getMembers(@PathVariable Long chatId) {
        List<com.btctech.mailapp.entity.User> members = chatService.getChatMembers(chatId);
        List<String> emails = members.stream()
                .map(u -> u.getEmail() != null ? u.getEmail() : u.getUsername())
                .collect(Collectors.toList());
        return ResponseEntity.ok(emails);
    }

    @PostMapping("/{chatId}/broadcast")
    public ResponseEntity<BroadcastResponse> sendBroadcast(
            @PathVariable Long chatId,
            @RequestBody BroadcastRequest request,
            org.springframework.security.core.Authentication authentication) {
        String senderEmail = authentication.getName();
        com.btctech.mailapp.entity.GroupBroadcast saved = chatService.saveBroadcast(chatId, senderEmail, request.getSubject(), request.getBody(), request.getAttachmentsJson());
        return ResponseEntity.ok(convertToBroadcastResponse(saved));
    }

    @GetMapping("/{chatId}/broadcasts")
    public ResponseEntity<List<BroadcastResponse>> getBroadcasts(
            @PathVariable Long chatId,
            org.springframework.security.core.Authentication authentication) {
        String userEmail = authentication != null ? authentication.getName() : null;
        List<com.btctech.mailapp.entity.GroupBroadcast> list = userEmail != null 
                ? chatService.getBroadcasts(chatId, userEmail)
                : chatService.getBroadcasts(chatId);
        List<BroadcastResponse> response = list.stream()
                .map(this::convertToBroadcastResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{chatId}/members/role")
    public ResponseEntity<?> updateMemberRole(
            @PathVariable Long chatId,
            @RequestBody ChatDTO.UpdateMemberRoleRequest request,
            org.springframework.security.core.Authentication authentication) {
        if (request == null || request.getEmail() == null || request.getRole() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Email and role are required"));
        }
        String requesterEmail = authentication.getName();
        Chat updated = chatService.updateMemberRole(chatId, request.getEmail(), request.getRole(), requesterEmail);
        return ResponseEntity.ok(convertToDTO(updated));
    }

    @PostMapping("/{chatId}/members/role")
    public ResponseEntity<?> updateMemberRolePost(
            @PathVariable Long chatId,
            @RequestBody ChatDTO.UpdateMemberRoleRequest request,
            org.springframework.security.core.Authentication authentication) {
        return updateMemberRole(chatId, request, authentication);
    }

    @lombok.Data
    public static class BroadcastRequest {
        private String subject;
        private String body;
        private String attachmentsJson;
    }

    @lombok.Data
    public static class BroadcastResponse {
        private Long id;
        private String subject;
        private String body;
        private String from;
        private String sentDate;
        private String attachmentsJson;
    }

    private BroadcastResponse convertToBroadcastResponse(com.btctech.mailapp.entity.GroupBroadcast broadcast) {
        BroadcastResponse response = new BroadcastResponse();
        response.setId(broadcast.getId());
        response.setSubject(broadcast.getSubject());
        response.setBody(broadcast.getBody());
        response.setFrom(broadcast.getSenderEmail());
        response.setSentDate(broadcast.getSentDate().toString());
        response.setAttachmentsJson(broadcast.getAttachmentsJson());
        return response;
    }

    @PostMapping("/{id}/leave")
    public ResponseEntity<?> leaveChat(@PathVariable Long id, org.springframework.security.core.Authentication authentication) {
        chatService.leaveChat(id, authentication.getName());
        return ResponseEntity.ok(Map.of("message", "Left chat successfully"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteChat(@PathVariable Long id, org.springframework.security.core.Authentication authentication) {
        chatService.deleteChat(id, authentication.getName());
        return ResponseEntity.ok(Map.of("message", "Chat deleted successfully"));
    }

    @PatchMapping("/{id}/name")
    public ResponseEntity<?> renameChat(@PathVariable Long id, @RequestBody Map<String, String> payload, org.springframework.security.core.Authentication authentication) {
        String newName = payload.get("name");
        if (newName == null || newName.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Name cannot be empty"));
        }
        Chat updated = chatService.renameChat(id, newName.trim(), authentication.getName());
        return ResponseEntity.ok(convertToDTO(updated));
    }
}
