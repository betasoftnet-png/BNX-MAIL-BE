package com.btctech.mailapp.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VerifyRecoveryOtpRequest {
    @JsonProperty("target")
    @JsonAlias({"type", "verificationType", "verification_type", "method", "targetType"})
    private String target; // "email" or "phone"

    @JsonProperty("contact")
    @JsonAlias({"email", "recoveryEmail", "recovery_email", "phone", "phoneNumber", "phone_number", "mobile", "mobileNumber", "mobile_number"})
    private String contact; // email address or 10-digit mobile number

    @JsonProperty("otp")
    @JsonAlias({"code", "otpCode", "otp_code", "verificationCode", "verification_code", "token"})
    private String otp; // 6-digit OTP code

    public String resolveTarget() {
        if (target != null && !target.trim().isEmpty()) {
            String t = target.trim().toLowerCase();
            if ("email".equals(t) || "mail".equals(t)) return "email";
            if ("phone".equals(t) || "mobile".equals(t) || "sms".equals(t) || "phonenumber".equals(t) || "mobilenumber".equals(t)) return "phone";
        }
        if (contact != null && contact.contains("@")) return "email";
        if (contact != null && contact.replaceAll("[^0-9]", "").length() >= 10) return "phone";
        return target != null ? target.trim().toLowerCase() : "email";
    }

    public String resolveContact() {
        return contact != null ? contact.trim() : "";
    }

    public String resolveOtp() {
        return otp != null ? otp.trim() : "";
    }
}
