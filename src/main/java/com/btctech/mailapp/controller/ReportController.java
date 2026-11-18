package com.btctech.mailapp.controller;

import com.btctech.mailapp.dto.ApiResponse;
import com.btctech.mailapp.entity.MailAccount;
import com.btctech.mailapp.entity.Report;
import com.btctech.mailapp.entity.SenderReputation;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.repository.MailAccountRepository;
import com.btctech.mailapp.repository.ReportRepository;
import com.btctech.mailapp.repository.SenderReputationRepository;
import com.btctech.mailapp.repository.UserRepository;
import com.btctech.mailapp.service.AdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportRepository reportRepository;
    private final MailAccountRepository mailAccountRepository;
    private final UserRepository userRepository;
    private final AdminService adminService;
    private final SenderReputationRepository senderReputationRepository;
    
    @PostMapping
    @Transactional
    public ResponseEntity<ApiResponse<String>> submitReport(@RequestBody Map<String, String> payload) {
        String reporterEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        String reportedEmail = payload.get("reportedEmail");
        String reason = payload.get("reason");
        String subject = payload.get("subject");
        
        if (reportedEmail == null || reason == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Reported email and reason are required."));
        }

        // Record spam reputation for reporter
        try {
            String u = reporterEmail != null ? reporterEmail.trim().toLowerCase() : null;
            String s = reportedEmail.trim().toLowerCase();
            if (u != null) {
                senderReputationRepository.findByUserEmailAndSenderEmail(u, s)
                        .ifPresentOrElse(rep -> {
                            rep.setStatus("REPORTED_SPAM");
                            rep.setNotes("User submitted spam report: " + reason);
                            rep.setUpdatedAt(java.time.LocalDateTime.now());
                            senderReputationRepository.save(rep);
                        }, () -> {
                            senderReputationRepository.save(SenderReputation.builder()
                                    .userEmail(u)
                                    .senderEmail(s)
                                    .status("REPORTED_SPAM")
                                    .notes("User submitted spam report: " + reason)
                                    .updatedAt(java.time.LocalDateTime.now())
                                    .build());
                        });
            }
        } catch (Exception ex) {
            log.warn("Notice saving sender reputation from report: {}", ex.getMessage());
        }
        
        // Find reporter
        Optional<MailAccount> reporterAccountOpt = mailAccountRepository.findByEmail(reporterEmail);
        if (reporterAccountOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Reporter not found."));
        }
        User reporter = userRepository.findById(reporterAccountOpt.get().getUserId()).orElse(null);
        
        // Find reported user
        Optional<MailAccount> reportedAccountOpt = mailAccountRepository.findByEmail(reportedEmail);
        if (reportedAccountOpt.isEmpty()) {
            // External user, we just return success but we don't ban external users
            log.info("Report received for external email {}", reportedEmail);
            return ResponseEntity.ok(ApiResponse.success("Report received.", "Thank you for reporting."));
        }
        User reportedUser = userRepository.findById(reportedAccountOpt.get().getUserId()).orElse(null);
        
        // Save report
        Report report = new Report();
        report.setReporter(reporter);
        report.setReportedUser(reportedUser);
        report.setReason(reason);
        report.setReportedEmailSubject(subject);
        reportRepository.save(report);
        
        // Auto-ban check
        long reportCount = reportRepository.countByReportedUserId(reportedUser.getId());
        if (reportCount >= 5) {
            log.warn("User {} hit 5 reports. Auto-banning...", reportedUser.getUsername());
            reportedUser.setActive(false);
            userRepository.save(reportedUser);
            try {
                adminService.forceLogout(reportedUser.getId());
                log.info("Forced logout for auto-banned user {}", reportedUser.getUsername());
            } catch (Exception e) {
                log.error("Error forcing logout for auto-banned user", e);
            }
        }
        
        return ResponseEntity.ok(ApiResponse.success("Report submitted", "Thank you for keeping BNX Mail safe."));
    }
}
