package com.btctech.mailapp.dto;

import lombok.Data;

@Data
public class CasboxEditRequest {
    private String body;
    private String content;

    public CasboxEditRequest() {
    }

    public CasboxEditRequest(String body) {
        this.body = body;
    }

    public CasboxEditRequest(String body, String content) {
        this.body = body;
        this.content = content;
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
