package com.btctech.mailapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FolderResult {
    private List<EmailDTO> emails;
    private int totalCount;
    private int unreadCount;

    public FolderResult(List<EmailDTO> emails, int totalCount) {
        this.emails = emails;
        this.totalCount = totalCount;
        this.unreadCount = 0;
    }
}
