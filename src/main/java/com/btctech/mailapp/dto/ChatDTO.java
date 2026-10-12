package com.btctech.mailapp.dto;

import com.btctech.mailapp.entity.ChatType;
import lombok.Data;
import java.util.List;

@Data
public class ChatDTO {
    private Long id;
    private String name;
    private ChatType type;
    private List<String> memberEmails;
    private String lastMessage;
    private String lastMessageTime;
    private Integer unreadCount;
    private String creatorEmail;
    private List<String> adminEmails;

    @Data
    public static class UpdateMemberRoleRequest {
        private String email;
        private String role;
    }

    @Data
    public static class CreateDirect {
        private String user1;
        private String user2;
    }

    @Data
    public static class CreateGroup {
        private String name;
        private List<String> members;
    }

    @Data
    public static class MessageRequest {
        private Long chatId;
        private String sender;
        private String message;
        private String attachmentsJson;
    }

    @Data
    public static class MessageResponse {
        private Long id;
        private Long chatId;
        private String sender;
        private String content;
        private String timestamp;
        private String attachmentsJson;
    }

    @Data
    public static class AddMembers {
        private List<String> emails;
    }

    @Data
    public static class InvitationResponse {
        private Long id;
        private Long chatId;
        private String chatName;
        private ChatType chatType;
        private String inviterEmail;
        private String status;
        private String createdAt;
    }
}
