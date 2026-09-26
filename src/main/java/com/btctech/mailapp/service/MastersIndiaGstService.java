package com.btctech.mailapp.service;

import com.btctech.mailapp.dto.mastersindia.MastersIndiaAuthRequest;
import com.btctech.mailapp.dto.mastersindia.MastersIndiaAuthResponse;
import com.btctech.mailapp.dto.mastersindia.MastersIndiaGstResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;

@Slf4j
@Service
public class MastersIndiaGstService {

    @Value("${mastersindia.client-id:}")
    private String clientId;

    @Value("${mastersindia.client-secret:}")
    private String clientSecret;

    @Value("${mastersindia.username:}")
    private String username;

    @Value("${mastersindia.password:}")
    private String password;

    @Value("${mastersindia.api-base-url:https://commonapi.mastersindia.co}")
    private String apiBaseUrl;

    private final RestTemplate restTemplate;

    public MastersIndiaGstService() {
        this.restTemplate = new RestTemplate();
        org.springframework.http.converter.json.MappingJackson2HttpMessageConverter converter = 
            new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter();
        converter.setSupportedMediaTypes(java.util.Arrays.asList(
            org.springframework.http.MediaType.APPLICATION_JSON,
            org.springframework.http.MediaType.valueOf("application/html"),
            org.springframework.http.MediaType.TEXT_HTML
        ));
        this.restTemplate.getMessageConverters().add(0, converter);
    }

    private String cachedAccessToken;
    private Instant tokenExpiryTime;

    private synchronized String getAccessToken() {
        if (cachedAccessToken != null && tokenExpiryTime != null && Instant.now().isBefore(tokenExpiryTime.minusSeconds(60))) {
            return cachedAccessToken;
        }

        log.info("Fetching new access token for Masters India API");
        String url = apiBaseUrl + "/oauth/access_token";

        MastersIndiaAuthRequest authRequest = MastersIndiaAuthRequest.builder()
                .username(username)
                .password(password)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .grantType("password")
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<MastersIndiaAuthRequest> request = new HttpEntity<>(authRequest, headers);

        try {
            ResponseEntity<MastersIndiaAuthResponse> response = restTemplate.postForEntity(url, request, MastersIndiaAuthResponse.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                cachedAccessToken = response.getBody().getAccessToken();
                // Set expiry time based on expires_in field (usually 14400 seconds)
                tokenExpiryTime = Instant.now().plusSeconds(response.getBody().getExpiresIn());
                log.info("Successfully fetched new access token, expires in {} seconds", response.getBody().getExpiresIn());
                return cachedAccessToken;
            } else {
                throw new RuntimeException("Failed to fetch access token, status: " + response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("Error fetching Masters India access token: {}", e.getMessage());
            throw new RuntimeException("Masters India Auth API Error: " + e.getMessage());
        }
    }

    public MastersIndiaGstResponse verifyGstin(String gstin) {
        String accessToken = getAccessToken();

        String url = UriComponentsBuilder.fromHttpUrl(apiBaseUrl + "/commonapis/searchgstin")
                .queryParam("gstin", gstin)
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        headers.set("client_id", clientId);

        HttpEntity<Void> request = new HttpEntity<>(headers);

        log.info("Calling Masters India GSTIN search API for GSTIN: {}", gstin);

        try {
            ResponseEntity<com.fasterxml.jackson.databind.JsonNode> rawResponse = restTemplate.exchange(url, HttpMethod.GET, request, com.fasterxml.jackson.databind.JsonNode.class);
            if (rawResponse.getStatusCode().is2xxSuccessful() && rawResponse.getBody() != null) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                com.fasterxml.jackson.databind.JsonNode rootNode = rawResponse.getBody();
                
                if (rootNode.has("error") && rootNode.get("error").asBoolean()) {
                    String errMsg = "GSTIN verification failed";
                    if (rootNode.has("data") && rootNode.get("data").isTextual()) {
                        errMsg += ": " + rootNode.get("data").asText();
                    } else if (rootNode.has("message") && rootNode.get("message").isTextual()) {
                        errMsg += ": " + rootNode.get("message").asText();
                    }
                    throw new RuntimeException(errMsg);
                }
                
                return mapper.treeToValue(rootNode, MastersIndiaGstResponse.class);
            } else {
                throw new RuntimeException("Failed to verify GSTIN, status: " + rawResponse.getStatusCode());
            }
        } catch (Exception e) {
            log.error("Error verifying GSTIN {}: {}", gstin, e.getMessage());
            throw new RuntimeException("Masters India GST API Error: " + e.getMessage());
        }
    }
}
