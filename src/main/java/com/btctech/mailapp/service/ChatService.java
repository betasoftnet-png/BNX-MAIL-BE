package com.btctech.mailapp.service;

import com.btctech.mailapp.entity.Chat;
import com.btctech.mailapp.entity.ChatMessage;
import com.btctech.mailapp.entity.ChatType;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.repository.ChatMessageRepository;
import com.btctech.mailapp.repository.ChatRepository;
import com.btctech.mailapp.repository.UserRepository;
import com.btctech.mailapp.entity.ChatInvitation;
import com.btctech.mailapp.entity.InvitationStatus;
import com.btctech.mailapp.entity.GroupBroadcast;
import com.btctech.mailapp.repository.ChatInvitationRepository;
import com.btctech.mailapp.repository.GroupBroadcastRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatService {
    private final ChatRepository chatRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;
    private final GroupBroadcastRepository groupBroadcastRepository;
    private final ChatInvitationRepository chatInvitationRepository;

    @Transactional
    public Chat createDirectChat(String email1, String email2) {
        User user1 = userRepository.findByEmail(email1)
                .orElseThrow(() -> new RuntimeException("User not found: " + email1));
        User user2 = userRepository.findByEmail(email2)
                .orElseThrow(() -> new RuntimeException("User not found: " + email2));

        Optional<Chat> existing = chatRepository.findDirectChatBetweenUsers(user1, user2);
        if (existing.isPresent()) {
            return existing.get();
        }

        Chat chat = new Chat();
        chat.setType(ChatType.DIRECT);
        chat.getMembers().add(user1);
        chat.getMembers().add(user2);
        return chatRepository.save(chat);
    }

    @Transactional
    public Chat createGroupChat(String name, List<String> memberEmails, String creatorEmail) {
        Chat chat = new Chat();
        chat.setName(name);
        chat.setType(ChatType.GROUP);
        
        Set<User> members = new HashSet<>();
        User creator = userRepository.findByEmail(creatorEmail).orElseGet(() -> userRepository.findByUsername(creatorEmail).orElse(null));
        if (creator != null) {
            members.add(creator);
            chat.setCreator(creator);
            chat.getAdmins().add(creator);
        }
        chat.setMembers(members);
        Chat savedChat = chatRepository.save(chat);

        for (String email : memberEmails) {
            if (email.equals(creatorEmail)) continue;
            userRepository.findByEmail(email).ifPresent(invitee -> {
                Optional<ChatInvitation> existing = chatInvitationRepository.findByChatAndInviteeAndStatus(savedChat, invitee, InvitationStatus.PENDING);
                if (existing.isEmpty()) {
                    ChatInvitation invitation = new ChatInvitation();
                    invitation.setChat(savedChat);
                    invitation.setInviter(creator);
                    invitation.setInvitee(invitee);
                    invitation.setStatus(InvitationStatus.PENDING);
                    chatInvitationRepository.save(invitation);
                }
            });
        }

        return savedChat;
    }

    public List<Chat> getUserChats(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found: " + email));
        return chatRepository.findByMembersContaining(user);
    }

    public List<ChatMessage> getChatMessages(Long chatId) {
        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new RuntimeException("Chat not found"));
        return chatMessageRepository.findByChatOrderByTimestampAsc(chat);
    }

    @Transactional
    public ChatMessage saveMessage(Long chatId, String senderEmail, String content, String attachmentsJson) {
        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new RuntimeException("Chat not found"));
        
        ChatMessage message = new ChatMessage();
        message.setChat(chat);
        message.setSenderEmail(senderEmail);
        message.setContent(content);
        message.setAttachmentsJson(attachmentsJson);
        return chatMessageRepository.save(message);
    }

    public boolean isChatAdmin(Chat chat, String userEmail) {
        if (chat == null || userEmail == null) return false;
        String trimmed = userEmail.trim().toLowerCase();
        if (chat.getCreator() != null) {
            String cEmail = chat.getCreator().getEmail() != null ? chat.getCreator().getEmail() : chat.getCreator().getUsername();
            if (cEmail != null && cEmail.trim().equalsIgnoreCase(trimmed)) {
                return true;
            }
        }
        if (chat.getAdmins() != null) {
            for (User u : chat.getAdmins()) {
                String aEmail = u.getEmail() != null ? u.getEmail() : u.getUsername();
                if (aEmail != null && aEmail.trim().equalsIgnoreCase(trimmed)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Transactional
    public Chat inviteMembersToGroup(Long chatId, List<String> emails, String inviterEmail) {
        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new RuntimeException("Chat not found"));
        if (chat.getType() != ChatType.GROUP) {
            throw new RuntimeException("Cannot add members to a non-group chat");
        }
        if (!isChatAdmin(chat, inviterEmail)) {
            throw new RuntimeException("Unauthorized: Only group Admins can add new members");
        }
        User inviter = userRepository.findByEmail(inviterEmail).orElseGet(() -> userRepository.findByUsername(inviterEmail).orElse(null));

        for (String email : emails) {
            userRepository.findByEmail(email).ifPresent(invitee -> {
                // Check if already a member
                if (chat.getMembers().contains(invitee)) return;

                Optional<ChatInvitation> existing = chatInvitationRepository.findByChatAndInviteeAndStatus(chat, invitee, InvitationStatus.PENDING);
                if (existing.isEmpty()) {
                    ChatInvitation invitation = new ChatInvitation();
                    invitation.setChat(chat);
                    invitation.setInviter(inviter);
                    invitation.setInvitee(invitee);
                    invitation.setStatus(InvitationStatus.PENDING);
                    chatInvitationRepository.save(invitation);
                }
            });
        }
        return chat;
    }

    public List<ChatInvitation> getPendingInvitations(String userEmail) {
        User user = userRepository.findByEmail(userEmail).orElseGet(() -> userRepository.findByUsername(userEmail).orElse(null));
        if (user == null) throw new RuntimeException("User not found: " + userEmail);
        return chatInvitationRepository.findByInviteeAndStatus(user, InvitationStatus.PENDING);
    }

    @Transactional
    public void deleteChat(Long chatId, String userEmail) {
        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new RuntimeException("Chat not found"));
        User user = userRepository.findByEmail(userEmail).orElseGet(() -> userRepository.findByUsername(userEmail).orElse(null));
        
        if (chat.getCreator() == null || user == null || !chat.getCreator().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized: Only the creator can delete the chat");
        }
        
        // Remove associated messages
        chatMessageRepository.findByChatOrderByTimestampAsc(chat).forEach(chatMessageRepository::delete);
        
        // Remove associated broadcasts
        groupBroadcastRepository.findByChatIdOrderBySentDateDesc(chatId).forEach(groupBroadcastRepository::delete);
        
        // Remove associated invitations
        chatInvitationRepository.deleteByChat(chat);
        
        chatRepository.delete(chat);
    }

    @Transactional
    public void leaveChat(Long chatId, String userEmail) {
        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new RuntimeException("Chat not found"));
        User user = userRepository.findByEmail(userEmail).orElseGet(() -> userRepository.findByUsername(userEmail).orElse(null));
        if (user == null) {
            throw new RuntimeException("User not found");
        }
        chat.getMembers().remove(user);
        chatRepository.save(chat);
    }

    @Transactional
    public void acceptInvitation(Long invitationId, String userEmail) {
        ChatInvitation invitation = chatInvitationRepository.findById(invitationId)
                .orElseThrow(() -> new RuntimeException("Invitation not found"));
        
        User invitee = invitation.getInvitee();
        if (!userEmail.equals(invitee.getEmail()) && !userEmail.equals(invitee.getUsername())) {
            throw new RuntimeException("Unauthorized");
        }
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new RuntimeException("Invitation is not pending");
        }

        invitation.setStatus(InvitationStatus.ACCEPTED);
        chatInvitationRepository.save(invitation);

        Chat chat = invitation.getChat();
        chat.getMembers().add(invitation.getInvitee());
        chatRepository.save(chat);
    }

    @Transactional
    public void rejectInvitation(Long invitationId, String userEmail) {
        ChatInvitation invitation = chatInvitationRepository.findById(invitationId)
                .orElseThrow(() -> new RuntimeException("Invitation not found"));
        
        User invitee = invitation.getInvitee();
        if (!userEmail.equals(invitee.getEmail()) && !userEmail.equals(invitee.getUsername())) {
            throw new RuntimeException("Unauthorized");
        }
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new RuntimeException("Invitation is not pending");
        }

        invitation.setStatus(InvitationStatus.REJECTED);
        chatInvitationRepository.save(invitation);
    }

    public List<User> getChatMembers(Long chatId) {
        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new RuntimeException("Chat not found"));
        return List.copyOf(chat.getMembers());
    }

    @Transactional
    public GroupBroadcast saveBroadcast(Long chatId, String senderEmail, String subject, String body, String attachmentsJson) {
        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new RuntimeException("Chat not found"));
        if (chat.getType() != ChatType.GROUP) {
            throw new RuntimeException("Cannot send broadcast to a non-group chat");
        }
        GroupBroadcast broadcast = GroupBroadcast.builder()
                .chat(chat)
                .senderEmail(senderEmail)
                .subject(subject)
                .body(body)
                .attachmentsJson(attachmentsJson)
                .sentDate(java.time.LocalDateTime.now())
                .build();
        return groupBroadcastRepository.save(broadcast);
    }

    public List<GroupBroadcast> getBroadcasts(Long chatId) {
        return groupBroadcastRepository.findByChatIdOrderBySentDateDesc(chatId);
    }

    public List<GroupBroadcast> getBroadcasts(Long chatId, String userEmail) {
        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new RuntimeException("Chat not found"));
        List<GroupBroadcast> all = groupBroadcastRepository.findByChatIdOrderBySentDateDesc(chatId);
        if (isChatAdmin(chat, userEmail)) {
            return all;
        }
        // Regular members only see their own TDocs / broadcasts
        if (userEmail == null) return Collections.emptyList();
        String trimmed = userEmail.trim().toLowerCase();
        return all.stream()
                .filter(b -> b.getSenderEmail() != null && b.getSenderEmail().trim().equalsIgnoreCase(trimmed))
                .collect(Collectors.toList());
    }

    @Transactional
    public Chat updateMemberRole(Long chatId, String targetEmail, String newRole, String requesterEmail) {
        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new RuntimeException("Chat not found"));
        if (chat.getType() != ChatType.GROUP) {
            throw new RuntimeException("Cannot manage roles in a non-group chat");
        }

        if (!isChatAdmin(chat, requesterEmail)) {
            throw new RuntimeException("Unauthorized: Only group Admins can manage roles");
        }

        User targetUser = userRepository.findByEmail(targetEmail)
                .orElseGet(() -> userRepository.findByUsername(targetEmail)
                .orElseThrow(() -> new RuntimeException("User not found: " + targetEmail)));

        if (!chat.getMembers().contains(targetUser)) {
            throw new RuntimeException("User is not a member of this Colab group");
        }

        if ("ADMIN".equalsIgnoreCase(newRole)) {
            chat.getAdmins().add(targetUser);
        } else if ("MEMBER".equalsIgnoreCase(newRole)) {
            chat.getAdmins().remove(targetUser);
        } else {
            throw new RuntimeException("Invalid role specified: " + newRole);
        }

        return chatRepository.save(chat);
    }

    @Transactional
    public Chat renameChat(Long chatId, String newName, String userEmail) {
        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new RuntimeException("Chat not found"));
        User user = userRepository.findByEmail(userEmail).orElseGet(() -> userRepository.findByUsername(userEmail).orElse(null));
        if (chat.getCreator() == null || user == null || !chat.getCreator().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized: Only the creator can rename the chat");
        }
        chat.setName(newName);
        return chatRepository.save(chat);
    }
}
