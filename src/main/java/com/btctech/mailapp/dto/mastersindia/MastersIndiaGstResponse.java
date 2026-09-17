package com.btctech.mailapp.dto.mastersindia;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MastersIndiaGstResponse {
    private Boolean error;
    private GstData data;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GstData {
        private String stjCd;
        private String dty;
        private String stj;
        private String lgnm;
        private com.fasterxml.jackson.databind.JsonNode adadr;
        private String cxdt;
        private String gstin;
        private com.fasterxml.jackson.databind.JsonNode nba;
        private String lstupdt;
        private String ctb;
        private String rgdt;
        private PrincipalAddress pradr;
        private String ctjCd;
        private String tradeNam;
        private String sts;
        private String ctj;
        private String einvoiceStatus;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PrincipalAddress {
        private Address addr;
        private String ntr;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Address {
        private String bnm;
        private String st;
        private String loc;
        private String bno;
        private String stcd;
        private String dst;
        private String city;
        private String flno;
        private String lt;
        private String pncd;
        private String lg;
    }
}
