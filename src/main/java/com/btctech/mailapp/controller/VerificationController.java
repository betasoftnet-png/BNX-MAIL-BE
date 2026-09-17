package com.btctech.mailapp.controller;

import com.btctech.mailapp.config.JwtUtil;
import com.btctech.mailapp.dto.ApiResponse;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.service.UserService;
import com.btctech.mailapp.service.VerificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/verification")
@RequiredArgsConstructor
public class VerificationController {

    private final VerificationService verificationService;
    private final UserService userService;
    private final JwtUtil jwtUtil;

    @PostMapping("/initiate/{emailId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initiate(
            @PathVariable Long emailId,
            @RequestHeader("Authorization") String authHeader) {

        try {
            String token = authHeader.substring(7);
            String email = jwtUtil.extractEmail(token);
            User user = userService.getUserByEmail(email);

            if (user == null) {
                return ResponseEntity.status(401).body(ApiResponse.error("User not found from token"));
            }

            String redirectUrl = verificationService.initiateVerification(user.getId(), emailId);

            Map<String, Object> data = new HashMap<>();
            data.put("redirectUrl", redirectUrl);

            return ResponseEntity.ok(ApiResponse.success(data, "Verification initiated"));
        } catch (Exception e) {
            log.error("Failed to initiate verification: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/status/{referenceId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkStatus(
            @PathVariable String referenceId) {

        try {
            String status = verificationService.checkAndFinalizeVerification(referenceId);

            Map<String, Object> data = new HashMap<>();
            data.put("status", status);

            return ResponseEntity.ok(ApiResponse.success(data, "Status retrieved"));
        } catch (Exception e) {
            log.error("Failed to check status: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/verify-pan/{emailId}")
    public ResponseEntity<ApiResponse<String>> verifyPanAndPromote(
            @PathVariable Long emailId,
            @RequestBody com.btctech.mailapp.dto.cashfree.CashfreePanRequest panRequest,
            @RequestHeader("Authorization") String authHeader) {

        try {
            String token = authHeader.substring(7);
            String email = jwtUtil.extractEmail(token);
            User user = userService.getUserByEmail(email);

            if (user == null) {
                return ResponseEntity.status(401).body(ApiResponse.error("User not found from token"));
            }

            boolean success = verificationService.verifyPanAndFinalize(user.getId(), emailId, panRequest.getPan(), panRequest.getName(), panRequest.getGstin());

            if (success) {
                return ResponseEntity.ok(ApiResponse.success("Success", "PAN verified successfully. Email is now primary."));
            } else {
                return ResponseEntity.badRequest().body(ApiResponse.error("PAN Verification failed or invalid details."));
            }
        } catch (Exception e) {
            log.error("Failed to verify PAN: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/verify-gst/{emailId}")
    public ResponseEntity<ApiResponse<String>> verifyGstAndPromote(
            @PathVariable Long emailId,
            @RequestBody Map<String, String> request,
            @RequestHeader("Authorization") String authHeader) {

        try {
            String token = authHeader.substring(7);
            String email = jwtUtil.extractEmail(token);
            User user = userService.getUserByEmail(email);

            if (user == null) {
                return ResponseEntity.status(401).body(ApiResponse.error("User not found from token"));
            }

            String gstin = request.get("gstin");
            if (gstin == null || gstin.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(ApiResponse.error("GSTIN is required"));
            }

            boolean success = verificationService.verifyGstAndFinalize(user.getId(), emailId, gstin);

            if (success) {
                return ResponseEntity.ok(ApiResponse.success("Success", "GSTIN verified successfully. Email is now primary."));
            } else {
                return ResponseEntity.badRequest().body(ApiResponse.error("GSTIN Verification failed."));
            }
        } catch (Exception e) {
            log.error("Failed to verify GSTIN: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Webhook for Cashfree
     * Note: In a real app, you should verify the signature of the webhook.
     */
    @PostMapping("/webhook")
    public ResponseEntity<String> handleWebhook(@RequestBody Map<String, Object> payload) {
        log.info("Received Cashfree webhook: {}", payload);
        
        try {
            // Logic to process webhook can be added here
            // For now, we rely on the frontend redirect check as it's simpler for integration testing
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            log.error("Webhook processing failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().body("FAILED");
        }
    }
    @GetMapping("/fetch-gstins")
    public ResponseEntity<ApiResponse<java.util.List<com.btctech.mailapp.dto.cashfree.GstinData>>> fetchGstins(
            @RequestParam String pan,
            @RequestHeader("Authorization") String authHeader) {
        try {
            String token = authHeader.substring(7);
            String email = jwtUtil.extractEmail(token);
            User user = userService.getUserByEmail(email);

            if (user == null) {
                return ResponseEntity.status(401).body(ApiResponse.error("User not found from token"));
            }

            java.util.List<com.btctech.mailapp.dto.cashfree.GstinData> activeGstins = verificationService.fetchActiveGstins(pan);
            return ResponseEntity.ok(ApiResponse.success(activeGstins, "Success"));
        } catch (Exception e) {
            log.error("Failed to fetch GSTINs: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
}
