package com.btctech.mailapp.service;

import com.btctech.mailapp.dto.EmailDTO;
import com.btctech.mailapp.dto.FolderResult;
import com.btctech.mailapp.dto.RecipientSuggestionDTO;
import com.btctech.mailapp.entity.CasboxMessage;
import com.btctech.mailapp.entity.Domain;
import com.btctech.mailapp.entity.MailAccount;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.repository.CasboxMessageRepository;
import com.btctech.mailapp.repository.DomainRepository;
import com.btctech.mailapp.repository.MailAccountRepository;
import com.btctech.mailapp.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RecipientSuggestionService {

    private final UserRepository userRepository;
    private final MailAccountRepository mailAccountRepository;
    private final DomainRepository domainRepository;
    private final CasboxMessageRepository casboxMessageRepository;
    private final MailReceiveService mailReceiveService;
    private final SessionService sessionService;

    // In-memory cache for recent contacts per user (5-minute TTL)
    private final Map<String, CachedContacts> contactsCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    private static final Set<String> EXTERNAL_DOMAINS = Set.of(
            "gmail.com", "googlemail.com",
            "yahoo.com", "ymail.com", "rocketmail.com",
            "outlook.com", "hotmail.com", "live.com", "msn.com",
            "zoho.com", "zohomail.com",
            "icloud.com", "me.com", "mac.com",
            "aol.com", "aim.com",
            "proton.me", "protonmail.com",
            "mail.com", "gmx.com", "yandex.com"
    );

    private static class CachedContacts {
        final long timestamp;
        final List<RecipientSuggestionDTO> contacts;

        CachedContacts(List<RecipientSuggestionDTO> contacts) {
            this.timestamp = System.currentTimeMillis();
            this.contacts = contacts;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    @Autowired
    public RecipientSuggestionService(UserRepository userRepository,
                                      MailAccountRepository mailAccountRepository,
                                      DomainRepository domainRepository,
                                      CasboxMessageRepository casboxMessageRepository,
                                      MailReceiveService mailReceiveService,
                                      SessionService sessionService) {
        this.userRepository = userRepository;
        this.mailAccountRepository = mailAccountRepository;
        this.domainRepository = domainRepository;
        this.casboxMessageRepository = casboxMessageRepository;
        this.mailReceiveService = mailReceiveService;
        this.sessionService = sessionService;
    }

    /**
     * Search recipients matching query.
     * Priority 1: Previously contacted users (from Sent and Received emails + Casbox messages)
     * Fallback: Registered BNXmail users from the user database (only registered BNXmail domains)
     */
    public List<RecipientSuggestionDTO> searchRecipients(String currentUserEmail,
                                                         String authToken,
                                                         String query,
                                                         int limit) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String cleanQuery = query.trim().toLowerCase();
        String myEmail = currentUserEmail != null ? currentUserEmail.trim().toLowerCase() : "";
        int maxResults = limit > 0 ? limit : 10;

        // 1. Priority 1: Retrieve and filter previously contacted users
        List<RecipientSuggestionDTO> allPreviousContacts = getPreviousContacts(myEmail, authToken);
        List<RecipientSuggestionDTO> matchingPreviousContacts = filterAndSortPreviousContacts(allPreviousContacts, cleanQuery, myEmail);

        // 2. Fallback: Search registered BNXmail users from database
        List<RecipientSuggestionDTO> fallbackBnxUsers = Collections.emptyList();
        if (matchingPreviousContacts.isEmpty() || matchingPreviousContacts.size() < maxResults) {
            fallbackBnxUsers = searchRegisteredBnxmailUsers(cleanQuery, myEmail);
        }

        // 3. Combine in priority order: previous contacts FIRST, then fallback registered BNXmail users
        List<RecipientSuggestionDTO> combined = new ArrayList<>();
        Set<String> seenEmails = new HashSet<>();
        if (!myEmail.isEmpty()) {
            seenEmails.add(myEmail);
        }

        // Add matching previous contacts first
        for (RecipientSuggestionDTO contact : matchingPreviousContacts) {
            if (contact != null && contact.getEmail() != null) {
                String norm = contact.getEmail().trim().toLowerCase();
                if (seenEmails.add(norm)) {
                    combined.add(contact);
                    if (combined.size() >= maxResults) break;
                }
            }
        }

        // Add fallback registered BNXmail users
        for (RecipientSuggestionDTO userDto : fallbackBnxUsers) {
            if (combined.size() >= maxResults) break;
            if (userDto != null && userDto.getEmail() != null) {
                String norm = userDto.getEmail().trim().toLowerCase();
                if (seenEmails.add(norm)) {
                    combined.add(userDto);
                }
            }
        }

        return combined;
    }

    /**
     * Get or fetch previous contacts for user.
     */
    public List<RecipientSuggestionDTO> getPreviousContacts(String userEmail, String authToken) {
        if (userEmail == null || userEmail.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String key = userEmail.trim().toLowerCase();
        CachedContacts cached = contactsCache.get(key);
        if (cached != null && !cached.isExpired()) {
            return cached.contacts;
        }

        Map<String, RecipientSuggestionDTO> contactsMap = new HashMap<>();

        // A. From Casbox Messages (sent and received)
        if (casboxMessageRepository != null) {
            try {
                List<CasboxMessage> messages = casboxMessageRepository.findAllMessagesForUser(userEmail);
                if (messages != null) {
                    for (CasboxMessage m : messages) {
                        String otherEmail = null;
                        if (key.equalsIgnoreCase(m.getSenderEmail())) {
                            otherEmail = m.getReceiverEmail();
                        } else if (key.equalsIgnoreCase(m.getReceiverEmail())) {
                            otherEmail = m.getSenderEmail();
                        }

                        if (otherEmail != null && !otherEmail.trim().isEmpty()) {
                            addOrUpdateContact(contactsMap, otherEmail.trim(), null, key);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Could not retrieve casbox messages for previous contacts: {}", e.getMessage());
            }
        }

        // B. From Sent & Received emails (via IMAP if session available)
        if (mailReceiveService != null && sessionService != null && authToken != null && !authToken.trim().isEmpty()) {
            try {
                String cleanToken = authToken.startsWith("Bearer ") ? authToken.substring(7) : authToken;
                String password = sessionService.getPasswordFromSession(cleanToken);
                if (password != null && !password.trim().isEmpty()) {
                    // Sent emails (recipients: to, cc, bcc)
                    try {
                        FolderResult sentResult = mailReceiveService.getSent(userEmail, password, 1, 50);
                        if (sentResult != null && sentResult.getEmails() != null) {
                            for (EmailDTO email : sentResult.getEmails()) {
                                processAddressField(contactsMap, email.getTo(), key);
                                processAddressField(contactsMap, email.getCc(), key);
                                processAddressField(contactsMap, email.getBcc(), key);
                            }
                        }
                    } catch (Exception sentEx) {
                        log.debug("Sent folder contacts check skipped: {}", sentEx.getMessage());
                    }

                    // Received emails (senders: from)
                    try {
                        FolderResult inboxResult = mailReceiveService.getInbox(userEmail, password, 1, 50);
                        if (inboxResult != null && inboxResult.getEmails() != null) {
                            for (EmailDTO email : inboxResult.getEmails()) {
                                processAddressField(contactsMap, email.getFrom(), key);
                            }
                        }
                    } catch (Exception inboxEx) {
                        log.debug("Inbox folder contacts check skipped: {}", inboxEx.getMessage());
                    }
                }
            } catch (Exception e) {
                log.debug("Mail contacts sync skipped: {}", e.getMessage());
            }
        }

        // Enrich contacts with names from user database if missing
        if (userRepository != null) {
            for (RecipientSuggestionDTO dto : contactsMap.values()) {
                if (dto.getName() == null || dto.getName().isEmpty() || dto.getName().equalsIgnoreCase(dto.getEmail().split("@")[0])) {
                    try {
                        Optional<User> uOpt = userRepository.findFirstByEmailIgnoreCase(dto.getEmail());
                        if (uOpt.isPresent()) {
                            User u = uOpt.get();
                            String fullName = getFullName(u);
                            if (fullName != null && !fullName.isEmpty()) {
                                dto.setName(fullName);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        List<RecipientSuggestionDTO> resultList = new ArrayList<>(contactsMap.values());
        contactsCache.put(key, new CachedContacts(resultList));
        return resultList;
    }

    /**
     * Filters and sorts previous contacts by typed query.
     */
    private List<RecipientSuggestionDTO> filterAndSortPreviousContacts(List<RecipientSuggestionDTO> contacts,
                                                                       String query,
                                                                       String currentUserEmail) {
        if (contacts == null || contacts.isEmpty()) {
            return Collections.emptyList();
        }

        String q = query.toLowerCase();

        return contacts.stream()
                .filter(c -> {
                    if (c == null || c.getEmail() == null) return false;
                    String emailLower = c.getEmail().toLowerCase();
                    if (currentUserEmail != null && emailLower.equalsIgnoreCase(currentUserEmail)) return false;
                    String nameLower = c.getName() != null ? c.getName().toLowerCase() : "";
                    return emailLower.contains(q) || nameLower.contains(q);
                })
                .sorted((a, b) -> {
                    String aEmail = a.getEmail().toLowerCase();
                    String bEmail = b.getEmail().toLowerCase();
                    String aName = a.getName() != null ? a.getName().toLowerCase() : "";
                    String bName = b.getName() != null ? b.getName().toLowerCase() : "";

                    // 1. Email starts with query (e.g. typing 'D' -> 'david@...' first)
                    boolean aEmailStarts = aEmail.startsWith(q);
                    boolean bEmailStarts = bEmail.startsWith(q);
                    if (aEmailStarts && !bEmailStarts) return -1;
                    if (!aEmailStarts && bEmailStarts) return 1;

                    // 2. Name starts with query (e.g. typing 'HR' -> 'HR Manager' first)
                    boolean aNameStarts = aName.startsWith(q);
                    boolean bNameStarts = bName.startsWith(q);
                    if (aNameStarts && !bNameStarts) return -1;
                    if (!aNameStarts && bNameStarts) return 1;

                    // 3. Any word in name starts with query
                    boolean aWordStarts = Arrays.stream(aName.split("\\s+")).anyMatch(w -> w.startsWith(q));
                    boolean bWordStarts = Arrays.stream(bName.split("\\s+")).anyMatch(w -> w.startsWith(q));
                    if (aWordStarts && !bWordStarts) return -1;
                    if (!aWordStarts && bWordStarts) return 1;

                    // 4. Higher contact count / frequency
                    if (a.getCount() != b.getCount()) {
                        return Integer.compare(b.getCount(), a.getCount());
                    }

                    // 5. Alphabetical
                    return aEmail.compareTo(bEmail);
                })
                .collect(Collectors.toList());
    }

    /**
     * Fallback search for registered BNXmail users only.
     * Excludes external email addresses (Gmail, Yahoo, Outlook, Zoho, etc.).
     * Only returns users whose email domain belongs to BNXmail.
     */
    public List<RecipientSuggestionDTO> searchRegisteredBnxmailUsers(String query, String currentUserEmail) {
        List<RecipientSuggestionDTO> results = new ArrayList<>();
        Set<String> seenEmails = new HashSet<>();
        if (currentUserEmail != null && !currentUserEmail.trim().isEmpty()) {
            seenEmails.add(currentUserEmail.trim().toLowerCase());
        }

        Set<String> bnxDomains = getBnxmailDomains();
        String q = query.toLowerCase();

        // 1. Search Mail Accounts
        if (mailAccountRepository != null) {
            try {
                List<MailAccount> accounts = mailAccountRepository.searchMailAccounts(query);
                if (accounts != null) {
                    for (MailAccount account : accounts) {
                        if (account == null || !Boolean.TRUE.equals(account.getActive())) continue;
                        String email = account.getEmail();
                        if (email == null || email.trim().isEmpty()) continue;
                        String cleanEmail = email.trim().toLowerCase();

                        // Must belong to BNXmail domain and NOT be an external email
                        if (!isBnxmailDomain(cleanEmail, bnxDomains) || isExternalEmailAddress(cleanEmail)) {
                            continue;
                        }

                        if (!seenEmails.add(cleanEmail)) continue;

                        String name = account.getEmailName();
                        if (userRepository != null && account.getUserId() != null) {
                            try {
                                Optional<User> uOpt = userRepository.findById(account.getUserId());
                                if (uOpt.isPresent()) {
                                    User u = uOpt.get();
                                    if (Boolean.FALSE.equals(u.getActive())) continue;
                                    String fullName = getFullName(u);
                                    if (fullName != null && !fullName.isEmpty()) {
                                        name = fullName;
                                    }
                                }
                            } catch (Exception ignored) {}
                        }

                        results.add(RecipientSuggestionDTO.builder()
                                .name(name != null ? name : cleanEmail.split("@")[0])
                                .email(cleanEmail)
                                .source("BNXMAIL_USER")
                                .isPreviousContact(false)
                                .count(0)
                                .build());
                    }
                }
            } catch (Exception e) {
                log.warn("Error searching mail accounts for registered BNXmail users: {}", e.getMessage());
            }
        }

        // 2. Search Users
        if (userRepository != null) {
            try {
                org.springframework.data.domain.Page<User> usersPage =
                        userRepository.searchUsers(query, PageRequest.of(0, 20));
                if (usersPage != null && usersPage.getContent() != null) {
                    for (User user : usersPage.getContent()) {
                        if (user == null || Boolean.FALSE.equals(user.getActive())) continue;

                        // Resolve registered BNXmail email
                        String bnxEmail = resolveBnxmailEmailForUser(user, bnxDomains);
                        if (bnxEmail == null || bnxEmail.trim().isEmpty()) continue;
                        String cleanEmail = bnxEmail.trim().toLowerCase();

                        // Strictly ensure no external emails from database
                        if (isExternalEmailAddress(cleanEmail) || !isBnxmailDomain(cleanEmail, bnxDomains)) {
                            continue;
                        }

                        if (!seenEmails.add(cleanEmail)) continue;

                        String name = getFullName(user);
                        results.add(RecipientSuggestionDTO.builder()
                                .name(name != null ? name : user.getUsername())
                                .email(cleanEmail)
                                .source("BNXMAIL_USER")
                                .isPreviousContact(false)
                                .count(0)
                                .build());
                    }
                }
            } catch (Exception e) {
                log.warn("Error searching users for registered BNXmail users: {}", e.getMessage());
            }
        }

        // Sort registered BNXmail users
        results.sort((a, b) -> {
            String aEmail = a.getEmail().toLowerCase();
            String bEmail = b.getEmail().toLowerCase();
            String aName = a.getName() != null ? a.getName().toLowerCase() : "";
            String bName = b.getName() != null ? b.getName().toLowerCase() : "";

            boolean aEmailStarts = aEmail.startsWith(q);
            boolean bEmailStarts = bEmail.startsWith(q);
            if (aEmailStarts && !bEmailStarts) return -1;
            if (!aEmailStarts && bEmailStarts) return 1;

            boolean aNameStarts = aName.startsWith(q);
            boolean bNameStarts = bName.startsWith(q);
            if (aNameStarts && !bNameStarts) return -1;
            if (!aNameStarts && bNameStarts) return 1;

            return aEmail.compareTo(bEmail);
        });

        return results;
    }

    private String resolveBnxmailEmailForUser(User user, Set<String> bnxDomains) {
        if (user == null) return null;

        // Check MailAccount table for user's registered account
        if (mailAccountRepository != null && user.getId() != null) {
            try {
                List<MailAccount> accounts = mailAccountRepository.findByUserId(user.getId());
                if (accounts != null) {
                    for (MailAccount ma : accounts) {
                        if (ma != null && Boolean.TRUE.equals(ma.getActive()) && ma.getEmail() != null) {
                            String email = ma.getEmail().trim().toLowerCase();
                            if (isBnxmailDomain(email, bnxDomains) && !isExternalEmailAddress(email)) {
                                return email;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // Check user's email field
        if (user.getEmail() != null && !user.getEmail().trim().isEmpty()) {
            String email = user.getEmail().trim().toLowerCase();
            if (isBnxmailDomain(email, bnxDomains) && !isExternalEmailAddress(email)) {
                return email;
            }
        }

        // Fallback to username@bnxmail.com if valid
        if (user.getUsername() != null && !user.getUsername().trim().isEmpty()) {
            String uName = user.getUsername().trim().toLowerCase();
            if (!uName.contains("@")) {
                return uName + "@bnxmail.com";
            } else if (isBnxmailDomain(uName, bnxDomains) && !isExternalEmailAddress(uName)) {
                return uName;
            }
        }

        return null;
    }

    private Set<String> getBnxmailDomains() {
        Set<String> domains = new HashSet<>();
        domains.add("bnxmail.com");

        if (domainRepository != null) {
            try {
                List<Domain> list = domainRepository.findAll();
                if (list != null) {
                    for (Domain d : list) {
                        if (d != null && Boolean.TRUE.equals(d.getActive()) && d.getDomain() != null) {
                            domains.add(d.getDomain().trim().toLowerCase());
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        return domains;
    }

    private boolean isBnxmailDomain(String email, Set<String> bnxDomains) {
        if (email == null || !email.contains("@")) return false;
        String domain = email.substring(email.lastIndexOf("@") + 1).trim().toLowerCase();
        return bnxDomains.contains(domain);
    }

    public static boolean isExternalEmailAddress(String email) {
        if (email == null) return false;
        String clean = email.trim().toLowerCase();
        int atIndex = clean.lastIndexOf('@');
        if (atIndex == -1 || atIndex == clean.length() - 1) {
            return false;
        }
        String domain = clean.substring(atIndex + 1);
        return EXTERNAL_DOMAINS.contains(domain);
    }

    private void processAddressField(Map<String, RecipientSuggestionDTO> map, String addressField, String currentUserEmail) {
        if (addressField == null || addressField.trim().isEmpty()) return;

        // Split by comma or semicolon
        String[] parts = addressField.split("[,;]");
        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) continue;
            String trimmed = part.trim();

            String name = null;
            String email = null;

            // Pattern: "Name" <email@domain.com> or Name <email@domain.com>
            Matcher matcher = Pattern.compile("^(?:[\"']?([^\"'<]+)[\"']?\\s*)?<([^>]+)>$").matcher(trimmed);
            if (matcher.find()) {
                name = matcher.group(1) != null ? matcher.group(1).trim() : null;
                email = matcher.group(2) != null ? matcher.group(2).trim().toLowerCase() : null;
            } else if (trimmed.contains("@")) {
                email = trimmed.replaceAll("[\"\'<>]", "").trim().toLowerCase();
                name = email.split("@")[0];
            }

            if (email != null && email.contains("@") && !email.equalsIgnoreCase(currentUserEmail)) {
                addOrUpdateContact(map, email, name, currentUserEmail);
            }
        }
    }

    private void addOrUpdateContact(Map<String, RecipientSuggestionDTO> map, String email, String name, String currentUserEmail) {
        if (email == null || email.trim().isEmpty()) return;
        String normEmail = email.trim().toLowerCase();
        if (normEmail.equalsIgnoreCase(currentUserEmail)) return;

        if (map.containsKey(normEmail)) {
            RecipientSuggestionDTO existing = map.get(normEmail);
            existing.setCount(existing.getCount() + 1);
            if (name != null && !name.trim().isEmpty() && !name.equalsIgnoreCase(normEmail.split("@")[0])) {
                existing.setName(name.trim());
            }
        } else {
            String displayName = (name != null && !name.trim().isEmpty()) ? name.trim() : normEmail.split("@")[0];
            map.put(normEmail, RecipientSuggestionDTO.builder()
                    .name(displayName)
                    .email(normEmail)
                    .source("PREVIOUS_CONTACT")
                    .isPreviousContact(true)
                    .count(1)
                    .build());
        }
    }

    private String getFullName(User user) {
        if (user == null) return null;
        String fn = user.getFirstName() != null ? user.getFirstName().trim() : "";
        String ln = user.getLastName() != null ? user.getLastName().trim() : "";
        String full = (fn + " " + ln).trim();
        if (!full.isEmpty()) return full;
        if (user.getDisplayName() != null && !user.getDisplayName().trim().isEmpty()) {
            return user.getDisplayName().trim();
        }
        return user.getUsername();
    }
}

