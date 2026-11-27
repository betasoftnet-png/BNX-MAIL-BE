package com.btctech.mailapp.dto;

import lombok.Data;

@Data
public class VerifyRecoveryOtpRequest {
    private String target; // "email" or "phone"
    private String contact; // email address or 10-digit mobile number
    private String otp; // 6-digit OTP code
}
