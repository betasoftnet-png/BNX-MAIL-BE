package com.btctech.mailapp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class CasboxSendRequest {
    @NotBlank(message = "Receiver email is required")
    @Pattern(regexp = "^[A-Za-z0-9+_.-]+@([A-Za-z0-9-]+\\.)+[A-Za-z]{2,}$", message = "Invalid recipient email address")
    private String receiverEmail;
    
    private String subject;
    
    @NotBlank(message = "Body is required")
    private String body;
    
    private String attachmentsJson;
}
