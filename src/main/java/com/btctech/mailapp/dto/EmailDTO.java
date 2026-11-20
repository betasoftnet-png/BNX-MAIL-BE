package com.btctech.mailapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailDTO {
    private String uid;
    private String messageId;
    private String from;
    private String avatarUrl;
    private String to;
    private String cc;
    private String bcc;
    private String subject;
    private String body;
    private String htmlBody;
    private Date sentDate;
    private Date receivedDate;
    
    @JsonProperty("isRead")
    private boolean isRead;
    
    @JsonProperty("isStarred")
    private boolean isStarred;

    @JsonProperty("isImportant")
    private boolean isImportant;

    private Double importanceScore;
    private String category;
    private String folderName;
    private boolean hasAttachments;
    private List<String> attachments;
    private int size;
    private java.util.List<com.btctech.mailapp.entity.MailLabel> labels;
}