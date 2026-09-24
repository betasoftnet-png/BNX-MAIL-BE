package com.btctech.mailapp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionStatusUpdateRequest {
    @NotBlank(message = "Status is required")
    private String status; // "CONNECTED" or "DISCONNECTED"
}
