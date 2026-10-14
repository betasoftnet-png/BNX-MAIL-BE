package com.btctech.mailapp.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class BlockedContactDTO {
    private String email;
    private LocalDateTime blockedAt;

    public BlockedContactDTO() {
    }

    @Builder
    public BlockedContactDTO(String email, LocalDateTime blockedAt) {
        this.email = email;
        this.blockedAt = blockedAt;
    }
}
