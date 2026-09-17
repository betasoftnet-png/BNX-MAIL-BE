package com.btctech.mailapp.dto.cashfree;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GstinData {

    @JsonProperty("gstin")
    private String gstin;

    @JsonProperty("status")
    private String status;

    @JsonProperty("state")
    private String state;

    @JsonProperty("legal_name")
    private String legalName;

    @JsonProperty("constitution_of_business")
    private String constitutionOfBusiness;
    
    @JsonProperty("center_jurisdiction")
    private String centerJurisdiction;
    
    @JsonProperty("state_jurisdiction")
    private String stateJurisdiction;
}
