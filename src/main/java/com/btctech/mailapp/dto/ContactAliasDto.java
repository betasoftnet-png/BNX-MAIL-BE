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
public class ContactAliasDto {
    private Long id;
    private Long ownerUserId;
    private Long contactUserId;
    private String contactUsername;
    private String contactEmail;
    private String customName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
