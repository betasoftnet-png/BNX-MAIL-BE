package com.btctech.mailapp.dto.cashfree;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CashfreeDigilockerDocumentResponse {

    @JsonProperty("reference_id")
    private String referenceId;
    
    @JsonProperty("verification_id")
    private String verificationId;

    private String status;
    private String message;
    
    private DocumentData document;
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DocumentData {
        private String name;
        private String dob;
        private String gender;
        
        @JsonProperty("document_number")
        private String documentNumber; // Aadhaar number
    }
}
