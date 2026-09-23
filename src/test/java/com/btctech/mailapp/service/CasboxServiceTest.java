package com.btctech.mailapp.service;

import com.btctech.mailapp.entity.CasboxMessage;
import com.btctech.mailapp.repository.CasboxMessageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CasboxServiceTest {

    @Test
    @DisplayName("deleteConversation with contact email should call repository deleteConversation")
    void testDeleteByContactEmail() {
        AtomicReference<String> deletedEmail1 = new AtomicReference<>();
        AtomicReference<String> deletedEmail2 = new AtomicReference<>();

        CasboxMessageRepository repo = (CasboxMessageRepository) Proxy.newProxyInstance(
                CasboxMessageRepository.class.getClassLoader(),
                new Class<?>[]{CasboxMessageRepository.class},
                (proxy, method, args) -> {
                    if ("deleteConversation".equals(method.getName())) {
                        deletedEmail1.set((String) args[0]);
                        deletedEmail2.set((String) args[1]);
                        return null;
                    }
                    return null;
                }
        );

        CasboxService service = new CasboxService(repo, null);
        service.deleteConversation("alice@example.com", "bob@example.com");

        assertEquals("alice@example.com", deletedEmail1.get());
        assertEquals("bob@example.com", deletedEmail2.get());
    }

    @Test
    @DisplayName("deleteConversation with numeric message ID where user is sender should delete conversation with receiver")
    void testDeleteByMessageIdSender() {
        AtomicReference<String> deletedEmail1 = new AtomicReference<>();
        AtomicReference<String> deletedEmail2 = new AtomicReference<>();

        CasboxMessage msg = new CasboxMessage();
        msg.setId(42L);
        msg.setSenderEmail("alice@example.com");
        msg.setReceiverEmail("bob@example.com");

        CasboxMessageRepository repo = (CasboxMessageRepository) Proxy.newProxyInstance(
                CasboxMessageRepository.class.getClassLoader(),
                new Class<?>[]{CasboxMessageRepository.class},
                (proxy, method, args) -> {
                    if ("findById".equals(method.getName())) {
                        return Optional.of(msg);
                    }
                    if ("deleteConversation".equals(method.getName())) {
                        deletedEmail1.set((String) args[0]);
                        deletedEmail2.set((String) args[1]);
                        return null;
                    }
                    return null;
                }
        );

        CasboxService service = new CasboxService(repo, null);
        service.deleteConversation("alice@example.com", "42");

        assertEquals("alice@example.com", deletedEmail1.get());
        assertEquals("bob@example.com", deletedEmail2.get());
    }

    @Test
    @DisplayName("deleteConversation with numeric message ID where user is receiver should delete conversation with sender")
    void testDeleteByMessageIdReceiver() {
        AtomicReference<String> deletedEmail1 = new AtomicReference<>();
        AtomicReference<String> deletedEmail2 = new AtomicReference<>();

        CasboxMessage msg = new CasboxMessage();
        msg.setId(42L);
        msg.setSenderEmail("bob@example.com");
        msg.setReceiverEmail("alice@example.com");

        CasboxMessageRepository repo = (CasboxMessageRepository) Proxy.newProxyInstance(
                CasboxMessageRepository.class.getClassLoader(),
                new Class<?>[]{CasboxMessageRepository.class},
                (proxy, method, args) -> {
                    if ("findById".equals(method.getName())) {
                        return Optional.of(msg);
                    }
                    if ("deleteConversation".equals(method.getName())) {
                        deletedEmail1.set((String) args[0]);
                        deletedEmail2.set((String) args[1]);
                        return null;
                    }
                    return null;
                }
        );

        CasboxService service = new CasboxService(repo, null);
        service.deleteConversation("alice@example.com", "42");

        assertEquals("alice@example.com", deletedEmail1.get());
        assertEquals("bob@example.com", deletedEmail2.get());
    }

    @Test
    @DisplayName("deleteConversation with numeric message ID where user is neither sender nor receiver should throw AccessDeniedException")
    void testDeleteByMessageIdUnauthorized() {
        CasboxMessage msg = new CasboxMessage();
        msg.setId(42L);
        msg.setSenderEmail("charlie@example.com");
        msg.setReceiverEmail("bob@example.com");

        CasboxMessageRepository repo = (CasboxMessageRepository) Proxy.newProxyInstance(
                CasboxMessageRepository.class.getClassLoader(),
                new Class<?>[]{CasboxMessageRepository.class},
                (proxy, method, args) -> {
                    if ("findById".equals(method.getName())) {
                        return Optional.of(msg);
                    }
                    return null;
                }
        );

        CasboxService service = new CasboxService(repo, null);
        assertThrows(AccessDeniedException.class, () -> {
            service.deleteConversation("alice@example.com", "42");
        });
    }

    @Test
    @DisplayName("deleteConversation with non-existent message ID should return cleanly without deleting")
    void testDeleteByMessageIdNotFound() {
        AtomicBoolean deleteCalled = new AtomicBoolean(false);

        CasboxMessageRepository repo = (CasboxMessageRepository) Proxy.newProxyInstance(
                CasboxMessageRepository.class.getClassLoader(),
                new Class<?>[]{CasboxMessageRepository.class},
                (proxy, method, args) -> {
                    if ("findById".equals(method.getName())) {
                        return Optional.empty();
                    }
                    if ("deleteConversation".equals(method.getName())) {
                        deleteCalled.set(true);
                    }
                    return null;
                }
        );

        CasboxService service = new CasboxService(repo, null);
        service.deleteConversation("alice@example.com", "9999");

        assertFalse(deleteCalled.get());
    }
}
