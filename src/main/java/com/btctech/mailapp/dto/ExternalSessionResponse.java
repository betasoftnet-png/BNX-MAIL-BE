package com.btctech.mailapp.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class ExternalSessionResponse {
    private Long id;
    private String appName;
    private String clientId;
    private LocalDateTime loggedInAt;
    private String ipAddress;
    private String userAgent;
    private String location;
    private String latitude;
    private String longitude;
    private Boolean revoked;
    private LocalDateTime revokedAt;
    private String status;
}
