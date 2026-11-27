package com.btctech.mailapp.dto;

import lombok.Data;

@Data
public class SendRecoveryOtpRequest {
    private String target; // "email" or "phone"
    private String contact; // email address or 10-digit mobile number
}
