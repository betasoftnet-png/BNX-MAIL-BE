package com.btctech.mailapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecipientSuggestionDTO {
    private String name;
    private String email;
    private String source; // "PREVIOUS_CONTACT" or "BNXMAIL_USER"
    private boolean isPreviousContact;
    private int count;
}

