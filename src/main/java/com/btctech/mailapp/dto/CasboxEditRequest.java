package com.btctech.mailapp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CasboxEditRequest {
    private String body;
    private String content;

    public CasboxEditRequest(String body) {
        this.body = body;
    }

    public String getCleanBody() {
        if (body != null && !body.trim().isEmpty()) {
            return body.trim();
        }
        if (content != null && !content.trim().isEmpty()) {
            return content.trim();
        }
        return "";
    }
}
