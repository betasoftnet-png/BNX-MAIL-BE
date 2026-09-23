package com.btctech.mailapp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class CasboxMessageDto {
    private Long id;
    private String senderEmail;
    private String receiverEmail;
    private String subject;
    private String body;
    private String attachmentsJson;
    private String status;
    private LocalDateTime timestamp;

    @JsonProperty("isArchived")
    private Boolean isArchived = false;

    @JsonProperty("senderArchived")
    private Boolean senderArchived = false;

    @JsonProperty("receiverArchived")
    private Boolean receiverArchived = false;

    @JsonProperty("archived")
    public Boolean getArchived() {
        return isArchived;
    }

    @JsonProperty("archived")
    public void setArchived(Boolean archived) {
        this.isArchived = archived;
    }
}
