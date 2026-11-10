package com.btctech.mailapp.service;

import com.btctech.mailapp.entity.AccountType;
import com.btctech.mailapp.dto.RegisterRequest;
import com.btctech.mailapp.dto.UserProfileDTO;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.entity.UserSettings;
import com.btctech.mailapp.exception.MailException;
import com.btctech.mailapp.repository.UserRepository;
import com.btctech.mailapp.repository.MailAccountRepository;
import com.btctech.mailapp.repository.UserSettingsRepository;
import com.btctech.mailapp.entity.MailAccount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.btctech.mailapp.strategy.RegistrationStrategy;
import org.springframework.beans.factory.annotation.Autowired;

@Slf4j
@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final MailAccountRepository mailAccountRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final com.btctech.mailapp.repository.ActivityLogRepository activityLogRepository;
    private final Map<String, RegistrationStrategy> registrationStrategies;
    private final SessionService sessionService;

    @Autowired
    public UserService(UserRepository userRepository, 
                       PasswordEncoder passwordEncoder,
                       MailAccountRepository mailAccountRepository,
                       UserSettingsRepository userSettingsRepository,
                       com.btctech.mailapp.repository.ActivityLogRepository activityLogRepository,
                       List<RegistrationStrategy> strategies,
                       SessionService sessionService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailAccountRepository = mailAccountRepository;
        this.userSettingsRepository = userSettingsRepository;
        this.activityLogRepository = activityLogRepository;
        this.sessionService = sessionService;
        this.registrationStrategies = strategies.stream()
                .collect(Collectors.toMap(RegistrationStrategy::getMode, Function.identity()));
    }

    /**
     * Get or create default user settings
     */
    @Transactional
    public UserSettings getSettings(User user) {
        return userSettingsRepository.findByUserId(user.getId())
                .orElseGet(() -> {
                    try {
                        log.info("Creating new default settings for user: {}", user.getEmail());
                        UserSettings settings = UserSettings.builder()
                                .user(user)
                                .inboxNotifications(true)
                                .sentNotifications(false)
                                .starredNotifications(true)
                                .snoozedNotifications(true)
                                .soundEnabled(true)
                                .vibrationEnabled(true)
                                .themeMode("Classic")
                                .accentColor("#4F46E5")
                                .fontSize(1.0)
                                .density("Default")
                                .storageLimit(16106127360L)
                                .fontFamily("Arial")
                                .textStyleFontSize("Normal")
                                .textColor("#000000")
                                .build();
                        return userSettingsRepository.save(settings);
                    } catch (org.springframework.dao.DataIntegrityViolationException e) {
                        log.warn("Settings already exist for user {} (race condition handled)", user.getEmail());
                        return userSettingsRepository.findByUserId(user.getId())
                            .orElseThrow(() -> new MailException("Failed to retrieve or create user settings"));
                    }
                });
    }

    /**
     * Update user settings
     */
    @Transactional
    public UserSettings updateSettings(User user, UserSettings newSettings) {
        UserSettings existing = getSettings(user);
        
        // Update User entity for recovery info (Sync phone number)
        if (newSettings.getPhoneNumber() != null) {
            existing.setPhoneNumber(newSettings.getPhoneNumber());
            user.setPhoneNumber(newSettings.getPhoneNumber());
            userRepository.save(user);
        }
        
        if (newSettings.getLocation() != null) existing.setLocation(newSettings.getLocation());
        if (newSettings.getJobTitle() != null) existing.setJobTitle(newSettings.getJobTitle());
        
        if (newSettings.getInboxNotifications() != null) existing.setInboxNotifications(newSettings.getInboxNotifications());
        if (newSettings.getSentNotifications() != null) existing.setSentNotifications(newSettings.getSentNotifications());
        if (newSettings.getStarredNotifications() != null) existing.setStarredNotifications(newSettings.getStarredNotifications());
        if (newSettings.getSnoozedNotifications() != null) existing.setSnoozedNotifications(newSettings.getSnoozedNotifications());
        if (newSettings.getSoundEnabled() != null) existing.setSoundEnabled(newSettings.getSoundEnabled());
        if (newSettings.getVibrationEnabled() != null) existing.setVibrationEnabled(newSettings.getVibrationEnabled());
        if (newSettings.getQuietHoursEnabled() != null) existing.setQuietHoursEnabled(newSettings.getQuietHoursEnabled());
        if (newSettings.getQuietHoursStart() != null) existing.setQuietHoursStart(newSettings.getQuietHoursStart());
        if (newSettings.getQuietHoursEnd() != null) existing.setQuietHoursEnd(newSettings.getQuietHoursEnd());
        
        if (newSettings.getThemeMode() != null) existing.setThemeMode(newSettings.getThemeMode());
        if (newSettings.getAccentColor() != null) existing.setAccentColor(newSettings.getAccentColor());
        if (newSettings.getFontSize() != null) existing.setFontSize(newSettings.getFontSize());
        if (newSettings.getDensity() != null) existing.setDensity(newSettings.getDensity());
        if (newSettings.getWallpaper() != null) existing.setWallpaper(newSettings.getWallpaper());
        if (newSettings.getUndoSendDelay() != null) existing.setUndoSendDelay(newSettings.getUndoSendDelay());
        if (newSettings.getSpellingCheckEnabled() != null) existing.setSpellingCheckEnabled(newSettings.getSpellingCheckEnabled());
        if (newSettings.getGrammarCheckEnabled() != null) existing.setGrammarCheckEnabled(newSettings.getGrammarCheckEnabled());
        if (newSettings.getAutoCorrectEnabled() != null) existing.setAutoCorrectEnabled(newSettings.getAutoCorrectEnabled());
        if (newSettings.getSmartComposeEnabled() != null) existing.setSmartComposeEnabled(newSettings.getSmartComposeEnabled());
        
        if (newSettings.getTwoFactorEnabled() != null) existing.setTwoFactorEnabled(newSettings.getTwoFactorEnabled());
        if (newSettings.getBiometricsEnabled() != null) existing.setBiometricsEnabled(newSettings.getBiometricsEnabled());
        if (newSettings.getLanguage() != null) existing.setLanguage(newSettings.getLanguage());
        if (newSettings.getReadingPaneMode() != null) existing.setReadingPaneMode(newSettings.getReadingPaneMode());
        if (newSettings.getFontFamily() != null) existing.setFontFamily(newSettings.getFontFamily());
        if (newSettings.getTextStyleFontSize() != null) existing.setTextStyleFontSize(newSettings.getTextStyleFontSize());
        if (newSettings.getTextColor() != null) existing.setTextColor(newSettings.getTextColor());
        if (newSettings.getCasboxAccepted() != null) {
            if (existing.getCasboxAccepted() == null) {
                existing.setCasboxAccepted(new java.util.ArrayList<>());
            }
            existing.getCasboxAccepted().clear();
            existing.getCasboxAccepted().addAll(newSettings.getCasboxAccepted());
        }
        
        return userSettingsRepository.save(existing);
    }

    /**
     * Reset ONLY the wallpaper preference to the application's default value for the user.
     */
    @Transactional
    public UserSettings resetWallpaper(User user) {
        UserSettings existing = getSettings(user);
        existing.setWallpaper("default");
        return userSettingsRepository.save(existing);
    }

    /**
     * Update User recovery info
     */
    @Transactional
    public void updateRecoveryInfo(User user, String recoveryEmail, String phoneNumber) {
        if (recoveryEmail != null) user.setRecoveryEmail(recoveryEmail);
        if (phoneNumber != null) {
            user.setPhoneNumber(phoneNumber);
            // Sync with UserSettings
            UserSettings settings = getSettings(user);
            settings.setPhoneNumber(phoneNumber);
            userSettingsRepository.save(settings);
        }
        userRepository.save(user);
        log.info("✓ Recovery info updated for user: {}", user.getUsername());
    }

    /**
     * Log user activity
     */
    @Transactional
    public void logActivity(User user, String activity, String details, String ipAddress, String deviceName) {
        com.btctech.mailapp.entity.ActivityLog logEntry = com.btctech.mailapp.entity.ActivityLog.builder()
                .user(user)
                .activity(activity)
                .details(details)
                .ipAddress(ipAddress)
                .deviceName(deviceName)
                .build();
        activityLogRepository.save(logEntry);
        log.info("Activity logged for {}: {}", user.getUsername(), activity);
    }

    public List<com.btctech.mailapp.entity.ActivityLog> getActivityLogs(User user) {
        return activityLogRepository.findTop20ByUserIdOrderByTimestampDesc(user.getId());
    }

    /**
     * Validate username
     */
    public void validateUsername(String username) {
        if (userRepository.existsByUsername(username)) {
            throw new MailException("Username already exists");
        }

        if (!username.equals(username.toLowerCase())) {
            throw new MailException("Username must be lowercase");
        }

        if (!username.matches("^[a-z0-9._-]+$")) {
            throw new MailException(
                    "Username can only contain lowercase letters, numbers, dots, hyphens and underscores");
        }
    }

    /**
     * Create user using strategies
     */
    @Transactional
    public User createUser(RegisterRequest request) {
        validateUsername(request.getUsername());

        RegistrationStrategy strategy = registrationStrategies.get(request.getMode().toUpperCase());
        if (strategy == null) {
            throw new MailException("Unsupported registration mode: " + request.getMode());
        }

        return strategy.register(request);
    }

    /**
     * Auth Result DTO
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class LoginResult {
        private User user;
        private boolean autoUpgraded;
    }

    /**
     * Authenticate user by email
     */
    public LoginResult authenticate(String email, String password) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new MailException("Invalid credentials"));

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new MailException("Invalid credentials");
        }

        boolean autoUpgraded = false;

        // AUTO-UPGRADE: CHILD to PUBLIC if 18+
        if (user.getAccountType() == AccountType.CHILD && user.getDob() != null) {
            int age = Period.between(user.getDob(), LocalDate.now()).getYears();
            if (age >= 18) {
                log.info("Auto-upgrading user {} from CHILD to PUBLIC (Age: {})", user.getUsername(), age);
                user.setAccountType(AccountType.PUBLIC);
                user.setRole("PUBLIC_USER");
                user.setApproved(true);
                autoUpgraded = true;
            }
        }

        // Update last login
        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);

        log.info("User authenticated: {}", email);
        return new LoginResult(user, autoUpgraded);
    }

    /**
     * Get user by email
     */
    public User getUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new MailException("User not found"));
    }

    /**
     * Get user by email or username
     * This handles cases where the token subject could be a username (temp token)
     * or email
     */
    public User getUserByEmailOrUsername(String identifier) {
        if (identifier.contains("@")) {
            try {
                return getUserByEmail(identifier);
            } catch (MailException e) {
                // FALLBACK: Check if this email belongs to any MailAccount
                return mailAccountRepository.findByEmail(identifier)
                        .map(ma -> userRepository.findById(ma.getUserId())
                                .orElseThrow(() -> new MailException("User not found for this mail account")))
                        .orElseThrow(() -> new MailException("User not found: " + identifier));
            }
        } else {
            return getUserByUsername(identifier);
        }
    }

    /**
     * ATOMIC: Update both User and Mail Account passwords
     */
    @Transactional
    public void updateUserPassword(User user, String newPlainPassword) {
        log.info("Performing atomic password update for user: {}", user.getUsername());
        
        // 1. Update User password (Hashed for Spring Auth)
        String userHash = passwordEncoder.encode(newPlainPassword);
        user.setPassword(userHash);
        userRepository.save(user);
        
        // 2. Update all associated MailAccount passwords (Hashed with {BLF-CRYPT} for Dovecot)
        String dovecotHash = "{BLF-CRYPT}" + userHash; // BCrypt hash is the same, just prefix it
        
        List<MailAccount> accounts = mailAccountRepository.findByUserId(user.getId());
        for (MailAccount account : accounts) {
            log.debug("Updating mail account: {}", account.getEmail());
            account.setPassword(dovecotHash);
            
            // 3. Store reversible encrypted password for background/app tasks (SMTP)
            try {
                account.setEncryptedPassword(sessionService.encrypt(newPlainPassword));
            } catch (Exception e) {
                log.error("Failed to encrypt SMTP password for account {}: {}", account.getEmail(), e.getMessage());
            }
            
            mailAccountRepository.save(account);
        }
        
        log.info("✓ Atomic password update complete for {} ({} mailboxes updated)", 
            user.getUsername(), accounts.size());
    }

    /**
     * Get user by username
     */
    public User getUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new MailException("User not found"));
    }

    /**
     * Approve a child account (Lifecycle Unlock)
     */
    @Transactional
    public User approveChild(User parent, Long childId) {
        // 1. Fetch child
        User child = userRepository.findById(childId)
                .orElseThrow(() -> new MailException("Child user not found"));

        if (!AccountType.CHILD.equals(child.getAccountType())) {
            throw new MailException("Target user is not a CHILD account");
        }

        // 2. Security Check: Parent ownership
        if (child.getParent() == null || !child.getParent().getId().equals(parent.getId())) {
            log.warn("Unauthorized approval attempt by user {} for child {}", parent.getUsername(), childId);
            throw new MailException("Unauthorized: You can only approve your own child accounts");
        }

        if (Boolean.TRUE.equals(child.getApproved())) {
            throw new MailException("Account is already approved");
        }

        // 3. Final Approval & Audit
        child.setApproved(true);
        child.setApprovedBy(parent.getId());
        child.setApprovedAt(LocalDateTime.now());
        
        User savedChild = userRepository.save(child);
        
        log.info("✓ CHILD account approved: {} by parent: {}", savedChild.getUsername(), parent.getUsername());
        
        return savedChild;
    }

    /**
     * Update user profile information (Google-like)
     */
    @Transactional
    public User updateProfile(User user, UserProfileDTO profileDto) {
        if (profileDto.getFirstName() != null) user.setFirstName(profileDto.getFirstName());
        if (profileDto.getLastName() != null) user.setLastName(profileDto.getLastName());
        if (profileDto.getNickname() != null) user.setNickname(profileDto.getNickname());
        if (profileDto.getDisplayName() != null) user.setDisplayName(profileDto.getDisplayName());
        if (profileDto.getGender() != null) user.setGender(profileDto.getGender());
        if (profileDto.getDob() != null && !profileDto.getDob().trim().isEmpty()) {
            try {
                user.setDob(LocalDate.parse(profileDto.getDob()));
            } catch (Exception e) {
                log.warn("Invalid dob format: {}", profileDto.getDob());
            }
        }
        if (profileDto.getPhoneNumber() != null) {
            user.setPhoneNumber(profileDto.getPhoneNumber());
            // Sync with UserSettings
            UserSettings settings = getSettings(user);
            settings.setPhoneNumber(profileDto.getPhoneNumber());
            userSettingsRepository.save(settings);
        }
        if (profileDto.getRecoveryEmail() != null) user.setRecoveryEmail(profileDto.getRecoveryEmail());
        if (profileDto.getHomeAddress() != null) user.setHomeAddress(profileDto.getHomeAddress());
        if (profileDto.getWorkAddress() != null) user.setWorkAddress(profileDto.getWorkAddress());
        if (profileDto.getOccupation() != null) user.setOccupation(profileDto.getOccupation());
        if (profileDto.getBio() != null) user.setBio(profileDto.getBio());
        
        return userRepository.save(user);
    }
}