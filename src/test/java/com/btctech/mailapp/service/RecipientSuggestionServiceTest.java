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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RecipientSuggestionServiceTest {

    private final String CURRENT_USER = "currentuser@bnxmail.com";

    private DomainRepository createDomainRepo() {
        Domain bnxDomain = new Domain();
        bnxDomain.setDomain("bnxmail.com");
        bnxDomain.setActive(true);

        return (DomainRepository) Proxy.newProxyInstance(
                DomainRepository.class.getClassLoader(),
                new Class<?>[]{DomainRepository.class},
                (proxy, method, args) -> {
                    if ("findAll".equals(method.getName())) {
                        return List.of(bnxDomain);
                    }
                    return null;
                }
        );
    }

    @Test
    @DisplayName("Should show matching previously contacted users first (Priority 1)")
    void testPreviousContactsPriority() {
        CasboxMessage msg1 = new CasboxMessage();
        msg1.setSenderEmail(CURRENT_USER);
        msg1.setReceiverEmail("david@external.com");

        CasboxMessage msg2 = new CasboxMessage();
        msg2.setSenderEmail("hr@company.com");
        msg2.setReceiverEmail(CURRENT_USER);

        CasboxMessageRepository casboxRepo = (CasboxMessageRepository) Proxy.newProxyInstance(
                CasboxMessageRepository.class.getClassLoader(),
                new Class<?>[]{CasboxMessageRepository.class},
                (proxy, method, args) -> {
                    if ("findAllMessagesForUser".equals(method.getName())) {
                        return List.of(msg1, msg2);
                    }
                    return Collections.emptyList();
                }
        );

        RecipientSuggestionService service = new RecipientSuggestionService(
                null, null, createDomainRepo(), casboxRepo, null, null
        );

        // When user types 'd'
        List<RecipientSuggestionDTO> resultsD = service.searchRecipients(CURRENT_USER, null, "d", 10);
        assertFalse(resultsD.isEmpty());
        assertEquals("david@external.com", resultsD.get(0).getEmail());
        assertTrue(resultsD.get(0).isPreviousContact());

        // When user types 'hr'
        List<RecipientSuggestionDTO> resultsHr = service.searchRecipients(CURRENT_USER, null, "hr", 10);
        assertFalse(resultsHr.isEmpty());
        assertEquals("hr@company.com", resultsHr.get(0).getEmail());
        assertTrue(resultsHr.get(0).isPreviousContact());
    }

    @Test
    @DisplayName("Should NOT search or return registered database users for partial names")
    void testDoNotSearchDatabaseUsersForPartialNames() {
        CasboxMessageRepository casboxRepo = (CasboxMessageRepository) Proxy.newProxyInstance(
                CasboxMessageRepository.class.getClassLoader(),
                new Class<?>[]{CasboxMessageRepository.class},
                (proxy, method, args) -> Collections.emptyList()
        );

        User bnxUser = new User();
        bnxUser.setId(101L);
        bnxUser.setUsername("alex");
        bnxUser.setFirstName("Alex");
        bnxUser.setLastName("Morgan");
        bnxUser.setEmail("alex@bnxmail.com");
        bnxUser.setActive(true);

        UserRepository userRepo = (UserRepository) Proxy.newProxyInstance(
                UserRepository.class.getClassLoader(),
                new Class<?>[]{UserRepository.class},
                (proxy, method, args) -> {
                    if ("searchUsers".equals(method.getName())) {
                        return new PageImpl<>(List.of(bnxUser));
                    }
                    return null;
                }
        );

        MailAccountRepository mailAccRepo = (MailAccountRepository) Proxy.newProxyInstance(
                MailAccountRepository.class.getClassLoader(),
                new Class<?>[]{MailAccountRepository.class},
                (proxy, method, args) -> Collections.emptyList()
        );

        RecipientSuggestionService service = new RecipientSuggestionService(
                userRepo, mailAccRepo, createDomainRepo(), casboxRepo, null, null
        );

        // When user types partial name 'alex' which is not in communication history,
        // it must NOT search or return database users
        List<RecipientSuggestionDTO> results = service.searchRecipients(CURRENT_USER, null, "alex", 10);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("Should NOT show new recipient suggestion for incomplete email addresses")
    void testIncompleteEmailDoesNotSuggestNewRecipient() {
        CasboxMessageRepository casboxRepo = (CasboxMessageRepository) Proxy.newProxyInstance(
                CasboxMessageRepository.class.getClassLoader(),
                new Class<?>[]{CasboxMessageRepository.class},
                (proxy, method, args) -> Collections.emptyList()
        );

        RecipientSuggestionService service = new RecipientSuggestionService(
                null, null, createDomainRepo(), casboxRepo, null, null
        );

        // Incomplete addresses must not trigger Add Recipient option
        List<RecipientSuggestionDTO> r1 = service.searchRecipients(CURRENT_USER, null, "sri", 10);
        assertTrue(r1.isEmpty());

        List<RecipientSuggestionDTO> r2 = service.searchRecipients(CURRENT_USER, null, "sri@", 10);
        assertTrue(r2.isEmpty());

        List<RecipientSuggestionDTO> r3 = service.searchRecipients(CURRENT_USER, null, "sri@bnxmail", 10);
        assertTrue(r3.isEmpty());

        List<RecipientSuggestionDTO> r4 = service.searchRecipients(CURRENT_USER, null, "sri@bnxmail.c", 10);
        assertTrue(r4.isEmpty());
    }

    @Test
    @DisplayName("Stage 2: Should lookup and return matching registered database user when complete email is entered")
    void testStage2RegisteredDatabaseUserLookup() {
        CasboxMessageRepository casboxRepo = (CasboxMessageRepository) Proxy.newProxyInstance(
                CasboxMessageRepository.class.getClassLoader(),
                new Class<?>[]{CasboxMessageRepository.class},
                (proxy, method, args) -> Collections.emptyList()
        );

        User bnxUser = new User();
        bnxUser.setId(201L);
        bnxUser.setUsername("sri");
        bnxUser.setFirstName("Sri");
        bnxUser.setLastName("Dev");
        bnxUser.setEmail("sri@bnxmail.com");
        bnxUser.setActive(true);

        UserRepository userRepo = (UserRepository) Proxy.newProxyInstance(
                UserRepository.class.getClassLoader(),
                new Class<?>[]{UserRepository.class},
                (proxy, method, args) -> {
                    if ("findByMailAccountEmailIgnoreCase".equals(method.getName()) || "findFirstByEmailIgnoreCase".equals(method.getName())) {
                        String email = (String) args[0];
                        if ("sri@bnxmail.com".equalsIgnoreCase(email)) {
                            return Optional.of(bnxUser);
                        }
                    }
                    return Optional.empty();
                }
        );

        RecipientSuggestionService service = new RecipientSuggestionService(
                userRepo, null, createDomainRepo(), casboxRepo, null, null
        );

        // When user enters complete email sri@bnxmail.com, registered user is found in database
        List<RecipientSuggestionDTO> results = service.searchRecipients(CURRENT_USER, null, "sri@bnxmail.com", 10);
        assertFalse(results.isEmpty());
        assertEquals(1, results.size());
        assertEquals("sri@bnxmail.com", results.get(0).getEmail());
        assertEquals("Sri Dev", results.get(0).getName());
        assertEquals("BNXMAIL_USER", results.get(0).getSource());
        assertEquals("DATABASE_USER", results.get(0).getCategory());
        assertFalse(results.get(0).isPreviousContact());
    }

    @Test
    @DisplayName("Stage 2: Should suggest Add Recipient when complete email has no matching registered database user")
    void testCompleteValidEmailSuggestsNewRecipientWhenNotRegistered() {
        CasboxMessageRepository casboxRepo = (CasboxMessageRepository) Proxy.newProxyInstance(
                CasboxMessageRepository.class.getClassLoader(),
                new Class<?>[]{CasboxMessageRepository.class},
                (proxy, method, args) -> Collections.emptyList()
        );

        UserRepository userRepo = (UserRepository) Proxy.newProxyInstance(
                UserRepository.class.getClassLoader(),
                new Class<?>[]{UserRepository.class},
                (proxy, method, args) -> Optional.empty()
        );

        RecipientSuggestionService service = new RecipientSuggestionService(
                userRepo, null, createDomainRepo(), casboxRepo, null, null
        );

        // Complete, valid email with no registered user must trigger Add Recipient option
        List<RecipientSuggestionDTO> results = service.searchRecipients(CURRENT_USER, null, "unregistered@bnxmail.com", 10);
        assertFalse(results.isEmpty());
        assertEquals("unregistered@bnxmail.com", results.get(0).getEmail());
        assertEquals("NEW_RECIPIENT", results.get(0).getSource());
        assertEquals("NEW_RECIPIENT", results.get(0).getCategory());
        assertFalse(results.get(0).isPreviousContact());
    }

    @Test
    @DisplayName("Stage 2: If address already exists in Inbox/Sent, do not duplicate as database recommendation")
    void testNoDuplicateDatabaseRecommendationIfInPreviousContacts() {
        CasboxMessage inboxMsg = new CasboxMessage();
        inboxMsg.setSenderEmail("sri@bnxmail.com");
        inboxMsg.setReceiverEmail(CURRENT_USER);

        CasboxMessageRepository casboxRepo = (CasboxMessageRepository) Proxy.newProxyInstance(
                CasboxMessageRepository.class.getClassLoader(),
                new Class<?>[]{CasboxMessageRepository.class},
                (proxy, method, args) -> List.of(inboxMsg)
        );

        User bnxUser = new User();
        bnxUser.setId(201L);
        bnxUser.setUsername("sri");
        bnxUser.setEmail("sri@bnxmail.com");
        bnxUser.setActive(true);

        UserRepository userRepo = (UserRepository) Proxy.newProxyInstance(
                UserRepository.class.getClassLoader(),
                new Class<?>[]{UserRepository.class},
                (proxy, method, args) -> Optional.of(bnxUser)
        );

        RecipientSuggestionService service = new RecipientSuggestionService(
                userRepo, null, createDomainRepo(), casboxRepo, null, null
        );

        // When sri@bnxmail.com is already in Inbox, return only 1 suggestion (Inbox contact), no duplicate DB suggestion
        List<RecipientSuggestionDTO> results = service.searchRecipients(CURRENT_USER, null, "sri@bnxmail.com", 10);
        assertEquals(1, results.size());
        assertEquals("sri@bnxmail.com", results.get(0).getEmail());
        assertTrue(results.get(0).isPreviousContact());
        assertNotEquals("DATABASE_USER", results.get(0).getCategory());
    }

    @Test
    @DisplayName("Should exclude current logged-in user from suggestions")
    void testExcludeCurrentUser() {
        CasboxMessage selfMsg = new CasboxMessage();
        selfMsg.setSenderEmail(CURRENT_USER);
        selfMsg.setReceiverEmail(CURRENT_USER);

        CasboxMessageRepository casboxRepo = (CasboxMessageRepository) Proxy.newProxyInstance(
                CasboxMessageRepository.class.getClassLoader(),
                new Class<?>[]{CasboxMessageRepository.class},
                (proxy, method, args) -> List.of(selfMsg)
        );

        RecipientSuggestionService service = new RecipientSuggestionService(
                null, null, createDomainRepo(), casboxRepo, null, null
        );

        List<RecipientSuggestionDTO> results = service.searchRecipients(CURRENT_USER, null, "current", 10);
        assertTrue(results.isEmpty());
    }
}
