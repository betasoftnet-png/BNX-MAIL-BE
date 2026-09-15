package com.btctech.mailapp.service;

import com.btctech.mailapp.dto.cashfree.CashfreeCreateUrlRequest;
import com.btctech.mailapp.dto.cashfree.CashfreeCreateUrlResponse;
import com.btctech.mailapp.dto.cashfree.CashfreeStatusResponse;
import com.btctech.mailapp.dto.cashfree.CashfreePanRequest;
import com.btctech.mailapp.dto.cashfree.CashfreePanResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.Collections;

@Slf4j
@Service
public class CashfreeService {

    @Value("${cashfree.client-id}")
    private String clientId;

    @Value("${cashfree.client-secret}")
    private String clientSecret;

    @Value("${cashfree.api-base-url:https://sandbox.cashfree.com/verification}")
    private String apiBaseUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    public CashfreeCreateUrlResponse createDigiLockerUrl(String referenceId, String redirectUrl) {
        String url = apiBaseUrl + "/digilocker";

        CashfreeCreateUrlRequest request = CashfreeCreateUrlRequest.builder()
                .verificationId(referenceId)
                .redirectUrl(redirectUrl)
                .documentRequested(Collections.singletonList("AADHAAR"))
                .userFlow("signup")
                .build();

        HttpHeaders headers = createHeadersV2();
        HttpEntity<CashfreeCreateUrlRequest> entity = new HttpEntity<>(request, headers);

        log.info("Calling Cashfree Create URL for reference: {}", referenceId);
        try {
            ResponseEntity<CashfreeCreateUrlResponse> response = restTemplate.postForEntity(url, entity, CashfreeCreateUrlResponse.class);
            if (response.getStatusCode() == HttpStatus.OK) {
                return response.getBody();
            } else {
                throw new RuntimeException("Failed to create DigiLocker URL: " + response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("Error calling Cashfree: {}", e.getMessage());
            throw new RuntimeException("Cashfree API Error: " + e.getMessage());
        }
    }

    public CashfreeStatusResponse getVerificationStatus(String verificationId) {
        String url = apiBaseUrl + "/digilocker?verification_id=" + verificationId;

        HttpHeaders headers = createHeadersV2();
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<CashfreeStatusResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, CashfreeStatusResponse.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Error checking Cashfree status: {}", e.getMessage());
            throw new RuntimeException("Failed to check status: " + e.getMessage());
        }
    }

    public com.btctech.mailapp.dto.cashfree.CashfreeDigilockerDocumentResponse getDigilockerDocument(String verificationId) {
        String url = apiBaseUrl + "/digilocker/document/AADHAAR?verification_id=" + verificationId;

        HttpHeaders headers = createHeadersV2();
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        log.info("Fetching DigiLocker document for verificationId: {}", verificationId);
        try {
            ResponseEntity<com.btctech.mailapp.dto.cashfree.CashfreeDigilockerDocumentResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, com.btctech.mailapp.dto.cashfree.CashfreeDigilockerDocumentResponse.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Error fetching DigiLocker document: {}", e.getMessage());
            throw new RuntimeException("Failed to fetch DigiLocker document: " + e.getMessage());
        }
    }

    public CashfreePanResponse verifyPan(String pan, String name) {
        String url = apiBaseUrl + "/pan";

        CashfreePanRequest request = CashfreePanRequest.builder()
                .pan(pan)
                .name(name)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-client-id", clientId);
        headers.set("x-client-secret", clientSecret);
        headers.set("x-api-version", "2023-12-18"); // Version required for PAN Verification

        HttpEntity<CashfreePanRequest> entity = new HttpEntity<>(request, headers);

        log.info("Calling Cashfree PAN Verification for PAN: {}", pan);
        try {
            ResponseEntity<CashfreePanResponse> response = restTemplate.postForEntity(url, entity, CashfreePanResponse.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Error calling Cashfree PAN API: {}", e.getMessage());
            throw new RuntimeException("Cashfree API Error: " + e.getMessage());
        }
    }

    public com.btctech.mailapp.dto.cashfree.CashfreePanAdvanceResponse verifyPanAdvance(String pan, String verificationId, String name) {
        String url = apiBaseUrl + "/pan/advance";

        com.btctech.mailapp.dto.cashfree.CashfreePanAdvanceRequest request = com.btctech.mailapp.dto.cashfree.CashfreePanAdvanceRequest.builder()
                .pan(pan)
                .verificationId(verificationId)
                .name(name)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-client-id", clientId);
        headers.set("x-client-secret", clientSecret);
        headers.set("x-api-version", "2023-12-18"); 

        HttpEntity<com.btctech.mailapp.dto.cashfree.CashfreePanAdvanceRequest> entity = new HttpEntity<>(request, headers);

        log.info("Calling Cashfree PAN Advance for PAN: {}", pan);
        try {
            ResponseEntity<com.btctech.mailapp.dto.cashfree.CashfreePanAdvanceResponse> response = restTemplate.postForEntity(url, entity, com.btctech.mailapp.dto.cashfree.CashfreePanAdvanceResponse.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Error calling Cashfree PAN Advance API: {}", e.getMessage());
            throw new RuntimeException("Cashfree API Error: " + e.getMessage());
        }
    }

    public com.btctech.mailapp.dto.cashfree.CashfreePanToGstinResponse getGstinByPan(String pan, String verificationId) {
        String url = apiBaseUrl + "/pan-gstin";

        com.btctech.mailapp.dto.cashfree.CashfreePanToGstinRequest request = com.btctech.mailapp.dto.cashfree.CashfreePanToGstinRequest.builder()
                .pan(pan)
                .verificationId(verificationId)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-client-id", clientId);
        headers.set("x-client-secret", clientSecret);
        headers.set("x-api-version", "2023-12-18"); 

        HttpEntity<com.btctech.mailapp.dto.cashfree.CashfreePanToGstinRequest> entity = new HttpEntity<>(request, headers);

        log.info("Calling Cashfree PAN to GSTIN for PAN: {}", pan);
        try {
            ResponseEntity<com.btctech.mailapp.dto.cashfree.CashfreePanToGstinResponse> response = restTemplate.postForEntity(url, entity, com.btctech.mailapp.dto.cashfree.CashfreePanToGstinResponse.class);
            log.info("Cashfree PAN-GSTIN raw response for PAN {}: {}", pan, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(response.getBody()));
            return response.getBody();
        } catch (Exception e) {
            log.error("Error calling Cashfree PAN to GSTIN API: {}", e.getMessage());
            throw new RuntimeException("Cashfree API Error: " + e.getMessage());
        }
    }

    public com.btctech.mailapp.dto.cashfree.CashfreeCinResponse verifyCin(String cin, String verificationId) {
        String url = apiBaseUrl + "/cin";

        com.btctech.mailapp.dto.cashfree.CashfreeCinRequest request = com.btctech.mailapp.dto.cashfree.CashfreeCinRequest.builder()
                .cin(cin)
                .verificationId(verificationId)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-client-id", clientId);
        headers.set("x-client-secret", clientSecret);
        headers.set("x-api-version", "2023-12-18");

        HttpEntity<com.btctech.mailapp.dto.cashfree.CashfreeCinRequest> entity = new HttpEntity<>(request, headers);

        log.info("Calling Cashfree CIN Verification for CIN: {}", cin);
        try {
            ResponseEntity<com.btctech.mailapp.dto.cashfree.CashfreeCinResponse> response = restTemplate.postForEntity(url, entity, com.btctech.mailapp.dto.cashfree.CashfreeCinResponse.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Error calling Cashfree CIN Verification API: {}", e.getMessage());
            throw new RuntimeException("Cashfree API Error: " + e.getMessage());
        }
    }

    @Value("${cashfree.api-version:2023-12-18}")
    private String apiVersion;

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-client-id", clientId);
        headers.set("x-client-secret", clientSecret);
        return headers;
    }

    private HttpHeaders createHeadersV2() {
        HttpHeaders headers = createHeaders();
        headers.set("x-api-version", apiVersion);
        return headers;
    }

    public com.btctech.mailapp.dto.cashfree.CashfreeGstinResponse verifyGstin(String gstin, String verificationId) {
        String url = apiBaseUrl + "/gstin";

        com.btctech.mailapp.dto.cashfree.CashfreeGstinRequest request = com.btctech.mailapp.dto.cashfree.CashfreeGstinRequest.builder()
                .gstin(gstin)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-client-id", clientId);
        headers.set("x-client-secret", clientSecret);
        headers.set("x-api-version", "2023-12-18"); 

        HttpEntity<com.btctech.mailapp.dto.cashfree.CashfreeGstinRequest> entity = new HttpEntity<>(request, headers);

        log.info("Calling Cashfree GSTIN for GSTIN: {}", gstin);
        try {
            ResponseEntity<com.btctech.mailapp.dto.cashfree.CashfreeGstinResponse> response = restTemplate.postForEntity(url, entity, com.btctech.mailapp.dto.cashfree.CashfreeGstinResponse.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Error calling Cashfree GSTIN API: {}", e.getMessage());
            throw new RuntimeException("Cashfree API Error: " + e.getMessage());
        }
    }
}
