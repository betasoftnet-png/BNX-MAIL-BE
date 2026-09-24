package com.btctech.mailapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConnectionDto {
    private Long id;
    private Long requesterId;
    private Long receiverId;
    private Long contactUserId;
    private String contactUsername;
    private String contactEmail;
    private String contactDisplayName;
    private String contactProfilePicture;
    private String contactProfilePictureUrl;
    private String status; // "CONNECTED", "DISCONNECTED", "ACCEPTED"
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
