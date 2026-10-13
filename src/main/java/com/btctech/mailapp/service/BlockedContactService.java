package com.btctech.mailapp.service;

import com.btctech.mailapp.dto.BlockedContactDTO;
import com.btctech.mailapp.entity.BlockedContact;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.repository.BlockedContactRepository;
import com.btctech.mailapp.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Slf4j
@Service
public class BlockedContactService {

    private final BlockedContactRepository blockedContactRepository;
    private final UserRepository userRepository;

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
    );

    @Autowired
    public BlockedContactService(BlockedContactRepository blockedContactRepository,
                                 @org.springframework.context.annotation.Lazy UserRepository userRepository) {
        this.blockedContactRepository = blockedContactRepository;
        this.userRepository = userRepository;
    }

    public BlockedContactService(BlockedContactRepository blockedContactRepository) {
        this(blockedContactRepository, null);
    }

    /**
     * Normalize email: trim leading/trailing whitespace and convert to lowercase.
     */
    public String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        return email.trim().toLowerCase();
    }

    /**
     * Resolve email or username identifier to normalized email address.
     */
    public String resolveToEmail(String identifier) {
        if (identifier == null) return null;
        String normalized = normalizeEmail(identifier);
        if (normalized == null || normalized.isEmpty()) return null;
        if (isValidEmail(normalized)) {
            return normalized;
        }
        if (userRepository != null) {
            try {
                Optional<User> u = userRepository.findByUsername(normalized);
                if (u.isPresent() && u.get().getEmail() != null) {
                    return normalizeEmail(u.get().getEmail());
                }
            } catch (Exception ignored) {}
        }
        return normalized;
    }

    /**
     * Validate email structure after normalization.
     */
    public boolean isValidEmail(String email) {
        if (email == null || email.trim().isEmpty()) {
            return false;
        }
        String normalized = normalizeEmail(email);
        return EMAIL_PATTERN.matcher(normalized).matches();
    }

    /**
     * Block a sender address for a specific authenticated user.
     */
    @Transactional
    public void blockSender(String userEmail, String rawSenderEmail) {
        String normalizedUser = resolveToEmail(userEmail);
        String normalizedSender = resolveToEmail(rawSenderEmail);

        if (normalizedUser == null || normalizedUser.isEmpty()) {
            throw new IllegalArgumentException("Authenticated user email is required");
        }

        if (normalizedSender == null || normalizedSender.isEmpty() || !isValidEmail(normalizedSender)) {
            throw new IllegalArgumentException("Invalid sender email address");
        }

        try {
            if (!blockedContactRepository.existsByUserEmailAndBlockedEmail(normalizedUser, normalizedSender)) {
                BlockedContact contact = BlockedContact.builder()
                        .userEmail(normalizedUser)
                        .blockedEmail(normalizedSender)
                        .blockedAt(LocalDateTime.now())
                        .build();
                blockedContactRepository.save(contact);
                log.info("✓ Blocked sender '{}' for user '{}'", normalizedSender, normalizedUser);
            } else {
                log.info("Sender '{}' is already blocked for user '{}'", normalizedSender, normalizedUser);
            }
        } catch (Exception e) {
            log.error("Database error blocking sender '{}' for user '{}': {}", normalizedSender, normalizedUser, e.getMessage(), e);
            throw new RuntimeException("Failed to save blocked contact: " + e.getMessage(), e);
        }
    }

    /**
     * Unblock a sender address for a specific authenticated user.
     */
    @Transactional
    public void unblockSender(String userEmail, String rawSenderEmail) {
        String normalizedUser = resolveToEmail(userEmail);
        String normalizedSender = resolveToEmail(rawSenderEmail);

        if (normalizedUser == null || normalizedUser.isEmpty()) {
            throw new IllegalArgumentException("Authenticated user email is required");
        }

        if (normalizedSender == null || normalizedSender.isEmpty()) {
            throw new IllegalArgumentException("Sender email address is required");
        }

        try {
            blockedContactRepository.deleteByUserEmailAndBlockedEmail(normalizedUser, normalizedSender);
            String rawSender = normalizeEmail(rawSenderEmail);
            if (rawSender != null && !rawSender.equalsIgnoreCase(normalizedSender)) {
                blockedContactRepository.deleteByUserEmailAndBlockedEmail(normalizedUser, rawSender);
            }
            log.info("✓ Unblocked sender '{}' for user '{}'", normalizedSender, normalizedUser);
        } catch (Exception e) {
            log.error("Database error unblocking sender '{}' for user '{}': {}", normalizedSender, normalizedUser, e.getMessage(), e);
            throw new RuntimeException("Failed to remove blocked contact: " + e.getMessage(), e);
        }
    }

    /**
     * Check whether a sender email is blocked for a user.
     */
    public boolean isSenderBlocked(String userEmail, String rawSenderEmail) {
        String normalizedUser = resolveToEmail(userEmail);
        String normalizedSender = resolveToEmail(rawSenderEmail);

        if (normalizedUser == null || normalizedSender == null || normalizedSender.isEmpty()) {
            return false;
        }

        try {
            if (blockedContactRepository.existsByUserEmailAndBlockedEmail(normalizedUser, normalizedSender)) {
                return true;
            }
            String rawSender = normalizeEmail(rawSenderEmail);
            if (rawSender != null && !rawSender.equalsIgnoreCase(normalizedSender)) {
                if (blockedContactRepository.existsByUserEmailAndBlockedEmail(normalizedUser, rawSender)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.error("Database error checking block status for user '{}' and sender '{}': {}", normalizedUser, normalizedSender, e.getMessage());
            return false;
        }
    }

    /**
     * Get optional BlockedContact entity for a user and sender.
     */
    public Optional<BlockedContact> getBlockedContact(String userEmail, String rawSenderEmail) {
        String normalizedUser = resolveToEmail(userEmail);
        String normalizedSender = resolveToEmail(rawSenderEmail);

        if (normalizedUser == null || normalizedSender == null) {
            return Optional.empty();
        }

        try {
            return blockedContactRepository.findByUserEmailAndBlockedEmail(normalizedUser, normalizedSender);
        } catch (Exception e) {
            log.error("Database error retrieving blocked contact for user '{}' and sender '{}': {}", normalizedUser, normalizedSender, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Get all blocked contacts for a specific authenticated user.
     */
    public List<BlockedContactDTO> getBlockedContacts(String userEmail) {
        String normalizedUser = resolveToEmail(userEmail);
        if (normalizedUser == null || normalizedUser.isEmpty()) {
            return List.of();
        }

        try {
            return blockedContactRepository.findByUserEmail(normalizedUser).stream()
                    .filter(c -> c != null && c.getBlockedEmail() != null)
                    .map(c -> new BlockedContactDTO(
                            c.getBlockedEmail(),
                            c.getBlockedAt() != null ? c.getBlockedAt() : LocalDateTime.now()
                    ))
                    .toList();
        } catch (Exception e) {
            log.error("Database error retrieving blocked contacts for user '{}': {}", normalizedUser, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Get raw list of blocked email strings for a user.
     */
    public List<String> getBlockedSenderEmails(String userEmail) {
        String normalizedUser = resolveToEmail(userEmail);
        if (normalizedUser == null || normalizedUser.isEmpty()) {
            return List.of();
        }

        try {
            return blockedContactRepository.findByUserEmail(normalizedUser).stream()
                    .filter(c -> c != null && c.getBlockedEmail() != null)
                    .map(BlockedContact::getBlockedEmail)
                    .map(String::toLowerCase)
                    .toList();
        } catch (Exception e) {
            log.error("Database error retrieving blocked sender emails for user '{}': {}", normalizedUser, e.getMessage(), e);
            return List.of();
        }
    }
}
