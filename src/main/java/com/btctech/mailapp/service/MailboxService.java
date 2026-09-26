package com.btctech.mailapp.service;

import com.btctech.mailapp.dto.CreateEmailRequest;
import com.btctech.mailapp.entity.Domain;
import com.btctech.mailapp.entity.MailAccount;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.exception.MailException;
import com.btctech.mailapp.repository.DomainRepository;
import com.btctech.mailapp.repository.MailAccountRepository;
import com.btctech.mailapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.SimpleMailMessage;
import java.io.File;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MailboxService {
    
    private final MailAccountRepository mailAccountRepository;
    private final DomainRepository domainRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SessionService sessionService;
    private final JavaMailSender javaMailSender;
    
    @Value("${mail.domain:btctech.shop}")
    private String mailDomain;
    
    @Value("${mail.storage.base-path:uploads/mail}")
    private String basePath;
    
    @Transactional
    public MailAccount createCustomEmail(User user, CreateEmailRequest request, String plainPassword, String overrideDomain) {
        try {
            log.info("Creating email for user: {}, email_name: {}, domain: {}", 
                user.getUsername(), request.getEmailName(), overrideDomain != null ? overrideDomain : mailDomain);
            
            validateEmailName(request.getEmailName());
            String activeDomain = (overrideDomain != null) ? overrideDomain : mailDomain;

            Long domainId = 1L;
            try {
                Domain domain = domainRepository.findByDomain(activeDomain)
                        .orElseGet(() -> {
                            Domain newDomain = new Domain();
                            newDomain.setDomain(activeDomain);
                            return domainRepository.save(newDomain);
                        });
                domainId = domain.getId();
            } catch (Exception e) {
                log.warn("Could not find/create Domain record for {}, using placeholder", activeDomain);
            }

            String fullEmail = request.getEmailName() + "@" + activeDomain;
            
            if (mailAccountRepository.existsByEmail(fullEmail)) {
                throw new MailException("Email already exists: " + fullEmail);
            }
            
            String maildirPath = basePath + "/" + activeDomain + "/" + request.getEmailName();
            
            boolean created = createMailboxDirectory(request.getEmailName(), activeDomain);
            if (!created) {
                throw new MailException("Failed to create mailbox directory");
            }
            
            String storedPassword = "{BLF-CRYPT}" + passwordEncoder.encode(plainPassword);
            
            MailAccount mailAccount = new MailAccount();
            mailAccount.setUserId(user.getId());
            mailAccount.setDomainId(domainId);
            mailAccount.setEmailName(request.getEmailName());
            mailAccount.setEmail(fullEmail);
            mailAccount.setMaildirPath(maildirPath);
            mailAccount.setPassword(storedPassword);

            // Reversible encryption for always-on SMTP access
            try {
                mailAccount.setEncryptedPassword(sessionService.encrypt(plainPassword));
            } catch (Exception e) {
                log.error("Failed to encrypt SMTP password during account creation: {}", e.getMessage());
            }
            
            long limitInBytes = 1073741824L; // 1GB
            if (com.btctech.mailapp.entity.AccountType.BUSINESS.equals(user.getAccountType())) {
                limitInBytes = 53687091200L; // 50GB
            } else if (com.btctech.mailapp.entity.AccountType.CHILD.equals(user.getAccountType())) {
                limitInBytes = 524288000L; // 500MB
            }
            mailAccount.setStorageLimit(limitInBytes);
            mailAccount.setStorageUsed(0L);
            mailAccount.setQuota(limitInBytes);
            
            mailAccount.setIsPrimary(false);
            mailAccount.setActive(true);
            
            mailAccount = mailAccountRepository.save(mailAccount);
            
            if (user.getEmail() == null || user.getEmail().isEmpty()) {
                user.setEmail(fullEmail);
                userRepository.save(user);
            }

            // Send welcome email to the newly created mailbox
            try {
                SimpleMailMessage welcomeMessage = new SimpleMailMessage();
                welcomeMessage.setFrom("welcome@" + activeDomain);
                welcomeMessage.setTo(fullEmail);
                welcomeMessage.setSubject("Welcome to BNX Mail!");
                welcomeMessage.setText("Dear " + user.getFirstName() + ",\n\n" +
                        "Welcome to BNX Mail! Your secure email account (" + fullEmail + ") is now active and ready to use.\n\n" +
                        "BNX Mail is designed with absolute privacy and advanced encryption to keep your digital identity and communications safe.\n\n" +
                        "Best regards,\n" +
                        "The BNX Mail Team");
                javaMailSender.send(welcomeMessage);
                log.info("Sent welcome email to new mailbox: {}", fullEmail);
            } catch (Exception e) {
                log.error("Failed to send welcome email to new mailbox {}: {}", fullEmail, e.getMessage());
            }
            
            return mailAccount;
            
        } catch (MailException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to create email: {}", e.getMessage(), e);
            throw new MailException("Failed to create email: " + e.getMessage());
        }
    }
    
    private boolean createMailboxDirectory(String emailName, String activeDomain) {
        try {
            String fullPath = basePath + "/" + activeDomain + "/" + emailName + "/Maildir";
            File maildirBase = new File(basePath + "/" + activeDomain + "/" + emailName);
            File maildir = new File(fullPath);
            new File(maildir, "new").mkdirs();
            new File(maildir, "cur").mkdirs();
            new File(maildir, "tmp").mkdirs();
            
            String[] folders = {"Sent", "Drafts", "Trash", "Spam", "Archive"};
            for (String folder : folders) {
                new File(maildir, "." + folder + "/new").mkdirs();
                new File(maildir, "." + folder + "/cur").mkdirs();
                new File(maildir, "." + folder + "/tmp").mkdirs();
            }
            
            String os = System.getProperty("os.name").toLowerCase();
            if (!os.contains("mac")) {
                try {
                    String[] chownCmd = {"chown", "-R", "vmail:vmail", maildirBase.getAbsolutePath()};
                    Process process = Runtime.getRuntime().exec(chownCmd);
                    process.waitFor();
                } catch (Exception e) {
                    log.warn("⚠ Could not change ownership: {}", e.getMessage());
                }
            }
            
            maildirBase.setReadable(true, false);
            maildirBase.setWritable(true, false);
            maildirBase.setExecutable(true, false);
            return true;
        } catch (Exception e) {
            log.error("Failed to create mailbox directory: {}", e.getMessage(), e);
            return false;
        }
    }
    
    private void validateEmailName(String emailName) {
        if (emailName == null || emailName.isEmpty()) throw new MailException("Email name is required");
        if (!emailName.equals(emailName.toLowerCase())) throw new MailException("Email name must be lowercase");
        if (!emailName.matches("^[a-z0-9._-]+$")) throw new MailException("Email name invalid characters");
        if (emailName.length() < 3 || emailName.length() > 30) throw new MailException("Email name length invalid");
    }
    
    public List<MailAccount> getUserEmails(Long userId) {
        return mailAccountRepository.findByUserId(userId);
    }
    
    public MailAccount getPrimaryEmail(Long userId) {
        return mailAccountRepository.findByUserIdAndIsPrimary(userId, true)
                .orElseThrow(() -> new MailException("No primary email found"));
    }
    
    public MailAccount getMailAccountByEmail(String email) {
        return mailAccountRepository.findByEmail(email)
                .orElseThrow(() -> new MailException("Mail account not found: " + email));
    }
    
    @Transactional
    public void setPrimaryEmail(Long userId, Long mailAccountId) {
        log.info("Setting primary email for user: {} to account ID: {}", userId, mailAccountId);
        
        List<MailAccount> accounts = mailAccountRepository.findByUserId(userId);
        for (MailAccount account : accounts) {
            account.setIsPrimary(false);
        }
        mailAccountRepository.saveAll(accounts);
        
        MailAccount primary = mailAccountRepository.findById(mailAccountId)
                .orElseThrow(() -> new MailException("Mail account not found"));
        
        if (!primary.getUserId().equals(userId)) {
            throw new MailException("Unauthorized");
        }
        
        primary.setIsPrimary(true);
        mailAccountRepository.save(primary);
        
        UserRepository userRepo = (UserRepository) userRepository;
        User user = userRepo.findById(userId).orElseThrow();
        user.setEmail(primary.getEmail());
        userRepo.save(user);
 
        log.info("✓ Successfully updated primary email to: {}", primary.getEmail());
    }

    public List<MailAccount> getAllEmails(String domain) {
        if (domain != null && !domain.isEmpty()) {
            return mailAccountRepository.findByEmailEndingWith("@" + domain);
        }
        return mailAccountRepository.findAll();
    }

    @Transactional
    public void saveMailAccount(MailAccount account) {
        mailAccountRepository.save(account);
    }
}