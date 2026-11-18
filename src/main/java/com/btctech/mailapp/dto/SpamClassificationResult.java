package com.btctech.mailapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpamClassificationResult {

    public enum Verdict {
        INBOX,
        REVIEW,
        SPAM
    }

    private Verdict verdict;

    /**
     * Mailbox category for display/grouping:
     * PRIMARY, PROMOTIONS, SOCIAL, UPDATES, JOB, REVIEW, SPAM
     */
    private String category;

    /**
     * Confidence score (0.0 = completely safe, 1.0 = definite spam/phishing)
     */
    private double spamScore;

    private double confidence;

    @Builder.Default
    private List<String> reasons = new ArrayList<>();

    private boolean isBlocked;
    private boolean isUnsubscribed;
    private boolean isUserReported;
    private boolean isPhishing;
    private boolean isSpoofed;
    private boolean hasMaliciousAttachment;
    private boolean isTrusted;
    private boolean isFirstTimeSender;
}
