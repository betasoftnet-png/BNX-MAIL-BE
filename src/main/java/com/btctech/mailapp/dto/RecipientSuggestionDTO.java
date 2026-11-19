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
    private String source; // "INBOX", "SENT", "BNXMAIL_USER", "NEW_RECIPIENT", etc.
    private String category; // "INBOX", "SENT", "DATABASE_USER", "NEW_RECIPIENT", etc.
    private boolean isPreviousContact;
    private int count;
}

