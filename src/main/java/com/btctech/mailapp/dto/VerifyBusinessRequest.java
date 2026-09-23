package com.btctech.mailapp.dto;

import lombok.Data;

@Data
public class VerifyBusinessRequest {
    private String type; // 'GSTIN' or 'LARGE_BUSINESS'
    private String pan; // Used for PAN-to-GSTIN
    private String gstin; // Used to verify within PAN-to-GSTIN list
}
