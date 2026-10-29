package com.btctech.mailapp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class SendMailRequest {

    @NotBlank(message = "Recipient email is required")
    private String to;

    private String cc;

    private String bcc;

    @NotBlank(message = "Subject is required")
    @Size(max = 500, message = "Subject cannot exceed 500 characters")
    private String subject;

    @NotBlank(message = "Email body is required")
    private String body;

    private String fromName;

    @JsonProperty("isHtml")
    private Boolean isHtml = false;

    @JsonProperty("isHtml")
    public Boolean getIsHtml() {
        return isHtml;
    }

    @JsonProperty("isHtml")
    public void setIsHtml(Boolean isHtml) {
        this.isHtml = isHtml;
    }

    private java.util.List<AttachmentInfo> attachments;

    private String uid;

    private Long id;

    private String draftUid;

    private Long draftId;

    @JsonProperty("isStarred")
    private Boolean isStarred;

    @JsonProperty("isStarred")
    public Boolean getIsStarred() {
        return isStarred;
    }

    @JsonProperty("isStarred")
    public void setIsStarred(Boolean isStarred) {
        this.isStarred = isStarred;
    }

    @JsonProperty("starred")
    public Boolean getStarred() {
        return isStarred;
    }

    @JsonProperty("starred")
    public void setStarred(Boolean starred) {
        this.isStarred = starred;
    }
}
