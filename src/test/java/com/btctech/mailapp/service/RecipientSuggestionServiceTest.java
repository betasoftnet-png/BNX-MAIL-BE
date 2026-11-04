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
    @DisplayName("Should fallback to registered BNXmail users when no previous contacts match")
    void testFallbackToRegisteredBnxmailUsers() {
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

        List<RecipientSuggestionDTO> results = service.searchRecipients(CURRENT_USER, null, "alex", 10);
        assertFalse(results.isEmpty());
        assertEquals("alex@bnxmail.com", results.get(0).getEmail());
        assertEquals("BNXMAIL_USER", results.get(0).getSource());
        assertFalse(results.get(0).isPreviousContact());
    }

    @Test
    @DisplayName("Should exclude external email addresses from registered user database fallback")
    void testExcludeExternalDomainsFromUserDatabase() {
        CasboxMessageRepository casboxRepo = (CasboxMessageRepository) Proxy.newProxyInstance(
                CasboxMessageRepository.class.getClassLoader(),
                new Class<?>[]{CasboxMessageRepository.class},
                (proxy, method, args) -> Collections.emptyList()
        );

        User externalUser = new User();
        externalUser.setId(102L);
        externalUser.setUsername("john");
        externalUser.setFirstName("John");
        externalUser.setLastName("Doe");
        externalUser.setEmail("johndoe@gmail.com");
        externalUser.setActive(true);

        UserRepository userRepo = (UserRepository) Proxy.newProxyInstance(
                UserRepository.class.getClassLoader(),
                new Class<?>[]{UserRepository.class},
                (proxy, method, args) -> {
                    if ("searchUsers".equals(method.getName())) {
                        return new PageImpl<>(List.of(externalUser));
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

        List<RecipientSuggestionDTO> results = service.searchRecipients(CURRENT_USER, null, "john", 10);
        // External gmail address must NOT be suggested from user database
        for (RecipientSuggestionDTO dto : results) {
            assertNotEquals("johndoe@gmail.com", dto.getEmail());
            assertFalse(dto.getEmail().endsWith("@gmail.com"));
        }
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
