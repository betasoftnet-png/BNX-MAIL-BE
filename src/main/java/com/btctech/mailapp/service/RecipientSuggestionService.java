package com.btctech.mailapp.service;

import com.btctech.mailapp.dto.EmailDTO;
import com.btctech.mailapp.dto.FolderResult;
import com.btctech.mailapp.dto.RecipientSuggestionDTO;
import com.btctech.mailapp.entity.CasboxMessage;
import com.btctech.mailapp.entity.Connection;
import com.btctech.mailapp.entity.Domain;
import com.btctech.mailapp.entity.MailAccount;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.repository.CasboxMessageRepository;
import com.btctech.mailapp.repository.ConnectionRepository;
import com.btctech.mailapp.repository.DomainRepository;
import com.btctech.mailapp.repository.MailAccountRepository;
import com.btctech.mailapp.repository.StarredEmailRepository;
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
    private final StarredEmailRepository starredEmailRepository;
    private final ConnectionRepository connectionRepository;

    // In-memory cache for recent contacts per user (5-minute TTL)
    private final Map<String, CachedContacts> contactsCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    private static final Pattern VALID_EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

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

    public RecipientSuggestionService(UserRepository userRepository,
                                      MailAccountRepository mailAccountRepository,
                                      DomainRepository domainRepository,
                                      CasboxMessageRepository casboxMessageRepository,
                                      MailReceiveService mailReceiveService,
                                      SessionService sessionService) {
        this(userRepository, mailAccountRepository, domainRepository, casboxMessageRepository, mailReceiveService, sessionService, null, null);
    }

    @Autowired
    public RecipientSuggestionService(UserRepository userRepository,
                                      MailAccountRepository mailAccountRepository,
                                      DomainRepository domainRepository,
                                      CasboxMessageRepository casboxMessageRepository,
                                      MailReceiveService mailReceiveService,
                                      SessionService sessionService,
                                      @Autowired(required = false) StarredEmailRepository starredEmailRepository,
                                      @Autowired(required = false) ConnectionRepository connectionRepository) {
        this.userRepository = userRepository;
        this.mailAccountRepository = mailAccountRepository;
        this.domainRepository = domainRepository;
        this.casboxMessageRepository = casboxMessageRepository;
        this.mailReceiveService = mailReceiveService;
        this.sessionService = sessionService;
        this.starredEmailRepository = starredEmailRepository;
        this.connectionRepository = connectionRepository;
    }

    public static boolean isValidEmailAddress(String email) {
        if (email == null) return false;
        String clean = email.trim();
        return VALID_EMAIL_PATTERN.matcher(clean).matches();
    }

    public static int getCategoryPriority(String category) {
        if (category == null) return 4;
        switch (category.toUpperCase()) {
            case "INBOX": return 1;
            case "SENT": return 2;
            case "BNXMAIL_USER":
            case "DATABASE_USER": return 3;
            case "STARRED":
            case "SAVED_CONTACT":
            case "CONTACT":
            case "PREVIOUS_CONTACT":
                return 4;
            case "NEW_RECIPIENT": return 5;
            default: return 4;
        }
    }

    /**
     * Search recipients matching query.
     * Two-Stage Architecture:
     * Stage 1: Existing communication contacts (Inbox -> Sent -> Starred -> Saved).
     * Stage 2: Database lookup ONLY when query is a complete valid email address with supported domain ending.
     */
    public List<RecipientSuggestionDTO> searchRecipients(String currentUserEmail,
                                                         String authToken,
                                                         String query,
                                                         int limit) {
        return searchRecipients(currentUserEmail, authToken, query, limit, null);
    }

    public List<RecipientSuggestionDTO> searchRecipients(String currentUserEmail,
                                                         String authToken,
                                                         String query,
                                                         int limit,
                                                         String mode) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String cleanQuery = query.trim().toLowerCase();
        String myEmail = currentUserEmail != null ? currentUserEmail.trim().toLowerCase() : "";
        int maxResults = limit > 0 ? limit : 10;
        boolean contactsOnly = "contacts".equalsIgnoreCase(mode);
        boolean databaseOnly = "database".equalsIgnoreCase(mode);

        // If mode=database: only lookup database user for complete valid email
        if (databaseOnly) {
            if (!isValidEmailAddress(cleanQuery)) {
                return Collections.emptyList();
            }
            Optional<RecipientSuggestionDTO> dbUser = lookupRegisteredUser(cleanQuery, myEmail);
            return dbUser.map(List::of).orElseGet(Collections::emptyList);
        }

        // Stage 1: Retrieve and filter existing communication contacts (Inbox, Sent, Starred, Saved)
        List<RecipientSuggestionDTO> allPreviousContacts = getPreviousContacts(myEmail, authToken);
        List<RecipientSuggestionDTO> matchingPreviousContacts = filterAndSortPreviousContacts(allPreviousContacts, cleanQuery, myEmail);

        List<RecipientSuggestionDTO> combined = new ArrayList<>();
        Set<String> seenEmails = new HashSet<>();
        if (!myEmail.isEmpty()) {
            seenEmails.add(myEmail);
        }

        // Add matching existing contacts in strict priority order (Inbox -> Sent -> Starred -> Saved)
        for (RecipientSuggestionDTO contact : matchingPreviousContacts) {
            if (contact != null && contact.getEmail() != null) {
                String norm = contact.getEmail().trim().toLowerCase();
                if (seenEmails.add(norm)) {
                    combined.add(contact);
                    if (combined.size() >= maxResults) break;
                }
            }
        }

        // If contactsOnly mode was requested, return only communication contacts
        if (contactsOnly) {
            return combined;
        }

        // Stage 2: For users not found in Inbox or Sent:
        // Trigger database lookup ONLY when user enters a complete email with valid domain ending
        if (isValidEmailAddress(cleanQuery) && !seenEmails.contains(cleanQuery) && combined.size() < maxResults) {
            // Priority 3: Matching registered database user
            Optional<RecipientSuggestionDTO> dbUser = lookupRegisteredUser(cleanQuery, myEmail);
            if (dbUser.isPresent()) {
                combined.add(dbUser.get());
                seenEmails.add(cleanQuery);
            } else {
                // Priority 4: Manual new email recipient if no registered match exists
                combined.add(RecipientSuggestionDTO.builder()
                        .name(cleanQuery.split("@")[0])
                        .email(cleanQuery)
                        .source("NEW_RECIPIENT")
                        .category("NEW_RECIPIENT")
                        .isPreviousContact(false)
                        .count(0)
                        .build());
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
                            addOrUpdateContact(contactsMap, otherEmail.trim(), null, key, "SAVED_CONTACT");
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Could not retrieve casbox messages for previous contacts: {}", e.getMessage());
            }
        }

        // B. From Saved Connections
        if (connectionRepository != null && userRepository != null) {
            try {
                Optional<User> meOpt = userRepository.findFirstByEmailIgnoreCase(userEmail);
                if (meOpt.isPresent()) {
                    List<Connection> conns = connectionRepository.findAcceptedConnectionsForUser(meOpt.get().getId());
                    if (conns != null) {
                        for (Connection c : conns) {
                            Long otherId = meOpt.get().getId().equals(c.getRequesterId()) ? c.getReceiverId() : c.getRequesterId();
                            if (otherId != null) {
                                Optional<User> otherUser = userRepository.findById(otherId);
                                if (otherUser.isPresent() && otherUser.get().getEmail() != null) {
                                    addOrUpdateContact(contactsMap, otherUser.get().getEmail(), getFullName(otherUser.get()), key, "SAVED_CONTACT");
                                }
                            }
                        }
                    }
                }
            } catch (Exception connEx) {
                log.debug("Connections contacts sync skipped: {}", connEx.getMessage());
            }
        }

        // C. From Email Communication History (Inbox, Sent, Starred) via IMAP if session available
        if (mailReceiveService != null && sessionService != null && authToken != null && !authToken.trim().isEmpty()) {
            try {
                String cleanToken = authToken.startsWith("Bearer ") ? authToken.substring(7) : authToken;
                String password = sessionService.getPasswordFromSession(cleanToken);
                if (password != null && !password.trim().isEmpty()) {
                    // Priority 1: Inbox received emails (senders)
                    try {
                        FolderResult inboxResult = mailReceiveService.getInbox(userEmail, password, 1, 50);
                        if (inboxResult != null && inboxResult.getEmails() != null) {
                            for (EmailDTO email : inboxResult.getEmails()) {
                                processAddressField(contactsMap, email.getFrom(), key, "INBOX");
                            }
                        }
                    } catch (Exception inboxEx) {
                        log.debug("Inbox folder contacts check skipped: {}", inboxEx.getMessage());
                    }

                    // Priority 2: Sent emails (recipients: to, cc, bcc)
                    try {
                        FolderResult sentResult = mailReceiveService.getSent(userEmail, password, 1, 50);
                        if (sentResult != null && sentResult.getEmails() != null) {
                            for (EmailDTO email : sentResult.getEmails()) {
                                processAddressField(contactsMap, email.getTo(), key, "SENT");
                                processAddressField(contactsMap, email.getCc(), key, "SENT");
                                processAddressField(contactsMap, email.getBcc(), key, "SENT");
                            }
                        }
                    } catch (Exception sentEx) {
                        log.debug("Sent folder contacts check skipped: {}", sentEx.getMessage());
                    }

                    // Priority 3: Starred emails
                    try {
                        FolderResult starredResult = mailReceiveService.getStarred(userEmail, password, 1, 50);
                        if (starredResult != null && starredResult.getEmails() != null) {
                            for (EmailDTO email : starredResult.getEmails()) {
                                processAddressField(contactsMap, email.getFrom(), key, "STARRED");
                                processAddressField(contactsMap, email.getTo(), key, "STARRED");
                            }
                        }
                    } catch (Exception starredEx) {
                        log.debug("Starred folder contacts check skipped: {}", starredEx.getMessage());
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
                    // 1. Suggestion Priority: 1. Inbox -> 2. Sent -> 3. Starred -> 4. Saved contacts
                    int aPriority = getCategoryPriority(a.getSource());
                    int bPriority = getCategoryPriority(b.getSource());
                    if (aPriority != bPriority) {
                        return Integer.compare(aPriority, bPriority);
                    }

                    String aEmail = a.getEmail().toLowerCase();
                    String bEmail = b.getEmail().toLowerCase();
                    String aName = a.getName() != null ? a.getName().toLowerCase() : "";
                    String bName = b.getName() != null ? b.getName().toLowerCase() : "";

                    // 2. Email starts with query (e.g. typing 'D' -> 'david@...' first)
                    boolean aEmailStarts = aEmail.startsWith(q);
                    boolean bEmailStarts = bEmail.startsWith(q);
                    if (aEmailStarts && !bEmailStarts) return -1;
                    if (!aEmailStarts && bEmailStarts) return 1;

                    // 3. Name starts with query (e.g. typing 'HR' -> 'HR Manager' first)
                    boolean aNameStarts = aName.startsWith(q);
                    boolean bNameStarts = bName.startsWith(q);
                    if (aNameStarts && !bNameStarts) return -1;
                    if (!aNameStarts && bNameStarts) return 1;

                    // 4. Any word in name starts with query
                    boolean aWordStarts = Arrays.stream(aName.split("\\s+")).anyMatch(w -> w.startsWith(q));
                    boolean bWordStarts = Arrays.stream(bName.split("\\s+")).anyMatch(w -> w.startsWith(q));
                    if (aWordStarts && !bWordStarts) return -1;
                    if (!aWordStarts && bWordStarts) return 1;

                    // 5. Higher contact count / frequency
                    if (a.getCount() != b.getCount()) {
                        return Integer.compare(b.getCount(), a.getCount());
                    }

                    // 6. Alphabetical
                    return aEmail.compareTo(bEmail);
                })
                .collect(Collectors.toList());
    }

    /**
     * Stage 2: Lookup registered database user for an exact, complete email address.
     * Searches MailAccount and User tables for active registered accounts.
     */
    public Optional<RecipientSuggestionDTO> lookupRegisteredUser(String email, String currentUserEmail) {
        if (email == null || !isValidEmailAddress(email)) {
            return Optional.empty();
        }
        String cleanEmail = email.trim().toLowerCase();
        if (currentUserEmail != null && cleanEmail.equalsIgnoreCase(currentUserEmail.trim())) {
            return Optional.empty();
        }

        Set<String> bnxDomains = getBnxmailDomains();

        // 1. Search MailAccount by exact email
        if (mailAccountRepository != null) {
            try {
                Optional<MailAccount> accOpt = mailAccountRepository.findByEmail(cleanEmail);
                if (accOpt.isPresent()) {
                    MailAccount acc = accOpt.get();
                    if (Boolean.TRUE.equals(acc.getActive())) {
                        String name = acc.getEmailName();
                        String avatarUrl = null;
                        if (userRepository != null && acc.getUserId() != null) {
                            try {
                                Optional<User> uOpt = userRepository.findById(acc.getUserId());
                                if (uOpt.isPresent() && !Boolean.FALSE.equals(uOpt.get().getActive())) {
                                    User u = uOpt.get();
                                    String fn = getFullName(u);
                                    if (fn != null && !fn.trim().isEmpty()) {
                                        name = fn.trim();
                                    }
                                    avatarUrl = resolveAvatarUrl(u, cleanEmail);
                                }
                            } catch (Exception ignored) {}
                        }
                        return Optional.of(RecipientSuggestionDTO.builder()
                                .name(name != null && !name.trim().isEmpty() ? name : cleanEmail.split("@")[0])
                                .email(cleanEmail)
                                .source("BNXMAIL_USER")
                                .category("DATABASE_USER")
                                .avatarUrl(avatarUrl)
                                .isPreviousContact(false)
                                .count(0)
                                .build());
                    }
                }
            } catch (Exception e) {
                log.warn("Error looking up mail account for {}: {}", cleanEmail, e.getMessage());
            }
        }

        // 2. Search User by email, recoveryEmail, or username
        if (userRepository != null) {
            try {
                Optional<User> uOpt = userRepository.findByMailAccountEmailIgnoreCase(cleanEmail);
                if (uOpt.isEmpty()) {
                    uOpt = userRepository.findFirstByEmailIgnoreCase(cleanEmail);
                }
                if (uOpt.isEmpty()) {
                    uOpt = userRepository.findFirstByRecoveryEmailIgnoreCase(cleanEmail);
                }
                if (uOpt.isEmpty() && isBnxmailDomain(cleanEmail, bnxDomains)) {
                    String username = cleanEmail.substring(0, cleanEmail.indexOf("@"));
                    uOpt = userRepository.findByUsernameIgnoreCase(username);
                }
                if (uOpt.isPresent()) {
                    User u = uOpt.get();
                    if (!Boolean.FALSE.equals(u.getActive())) {
                        String name = getFullName(u);
                        String avatarUrl = resolveAvatarUrl(u, cleanEmail);
                        return Optional.of(RecipientSuggestionDTO.builder()
                                .name(name != null && !name.trim().isEmpty() ? name : u.getUsername())
                                .email(cleanEmail)
                                .source("BNXMAIL_USER")
                                .category("DATABASE_USER")
                                .avatarUrl(avatarUrl)
                                .isPreviousContact(false)
                                .count(0)
                                .build());
                    }
                }
            } catch (Exception e) {
                log.warn("Error looking up user for {}: {}", cleanEmail, e.getMessage());
            }
        }

        return Optional.empty();
    }

    private String resolveAvatarUrl(User user, String cleanEmail) {
        if (user == null) return null;
        if (user.getProfilePicture() != null && !user.getProfilePicture().trim().isEmpty()) {
            String pic = user.getProfilePicture().trim();
            if (pic.startsWith("data:") || pic.startsWith("http://") || pic.startsWith("https://")) {
                return pic;
            }
            return "/api/users/profile-picture/" + cleanEmail;
        }
        return null;
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
        processAddressField(map, addressField, currentUserEmail, "INBOX");
    }

    private void processAddressField(Map<String, RecipientSuggestionDTO> map, String addressField, String currentUserEmail, String category) {
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
                addOrUpdateContact(map, email, name, currentUserEmail, category);
            }
        }
    }

    private void addOrUpdateContact(Map<String, RecipientSuggestionDTO> map, String email, String name, String currentUserEmail) {
        addOrUpdateContact(map, email, name, currentUserEmail, "INBOX");
    }

    private void addOrUpdateContact(Map<String, RecipientSuggestionDTO> map, String email, String name, String currentUserEmail, String category) {
        if (email == null || email.trim().isEmpty()) return;
        String normEmail = email.trim().toLowerCase();
        if (normEmail.equalsIgnoreCase(currentUserEmail)) return;

        String assignedCategory = category != null ? category.toUpperCase() : "INBOX";
        int newPriority = getCategoryPriority(assignedCategory);

        if (map.containsKey(normEmail)) {
            RecipientSuggestionDTO existing = map.get(normEmail);
            existing.setCount(existing.getCount() + 1);
            if (name != null && !name.trim().isEmpty() && !name.equalsIgnoreCase(normEmail.split("@")[0])) {
                existing.setName(name.trim());
            }
            int existingPriority = getCategoryPriority(existing.getCategory() != null ? existing.getCategory() : existing.getSource());
            if (newPriority < existingPriority) {
                existing.setSource(assignedCategory);
                existing.setCategory(assignedCategory);
            }
        } else {
            String displayName = (name != null && !name.trim().isEmpty()) ? name.trim() : normEmail.split("@")[0];
            map.put(normEmail, RecipientSuggestionDTO.builder()
                    .name(displayName)
                    .email(normEmail)
                    .source(assignedCategory)
                    .category(assignedCategory)
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

