package com.btctech.mailapp.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContactAliasRequest {
    @Size(max = 150, message = "Custom name cannot exceed 150 characters")
    private String customName;
}
