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

    @Test
    @DisplayName("getThread preserves historical messages even when contact is blocked")
    void testGetThreadPreservesHistoricalMessagesWhenContactBlocked() {
        CasboxMessage msg1 = new CasboxMessage();
        msg1.setId(1L);
        msg1.setSenderEmail("alice@example.com");
        msg1.setReceiverEmail("bob@example.com");
        msg1.setBody("Hello Bob");
        msg1.setStatus("SENT");
        msg1.setTimestamp(java.time.LocalDateTime.now().minusHours(2));

        CasboxMessage msg2 = new CasboxMessage();
        msg2.setId(2L);
        msg2.setSenderEmail("bob@example.com");
        msg2.setReceiverEmail("alice@example.com");
        msg2.setBody("Hi Alice");
        msg2.setStatus("DELIVERED");
        msg2.setTimestamp(java.time.LocalDateTime.now().minusHours(1));

        CasboxMessageRepository repo = (CasboxMessageRepository) Proxy.newProxyInstance(
                CasboxMessageRepository.class.getClassLoader(),
                new Class<?>[]{CasboxMessageRepository.class},
                (proxy, method, args) -> {
                    if ("findConversation".equals(method.getName())) {
                        return List.of(msg1, msg2);
                    }
                    return null;
                }
        );

        com.btctech.mailapp.repository.BlockedContactRepository blockedRepo =
                (com.btctech.mailapp.repository.BlockedContactRepository) Proxy.newProxyInstance(
                        com.btctech.mailapp.repository.BlockedContactRepository.class.getClassLoader(),
                        new Class<?>[]{com.btctech.mailapp.repository.BlockedContactRepository.class},
                        (proxy, method, args) -> {
                            if ("existsByUserEmailAndBlockedEmail".equals(method.getName())) {
                                String user = (String) args[0];
                                String blocked = (String) args[1];
                                return "alice@example.com".equals(user) && "bob@example.com".equals(blocked);
                            }
                            return false;
                        }
                );

        BlockedContactService blockedService = new BlockedContactService(blockedRepo, null);
        CasboxService service = new CasboxService(repo, null, null, null, null, null, blockedService);

        List<com.btctech.mailapp.dto.CasboxMessageDto> thread = service.getThread("alice@example.com", "bob@example.com");
        assertEquals(2, thread.size(), "All historical messages must remain visible after block");
        assertEquals("Hello Bob", thread.get(0).getBody());
        assertEquals("Hi Alice", thread.get(1).getBody());
    }

    @Test
    @DisplayName("getAllMessages preserves historical messages even when contact is blocked")
    void testGetAllMessagesPreservesHistoricalMessagesWhenContactBlocked() {
        CasboxMessage msg1 = new CasboxMessage();
        msg1.setId(1L);
        msg1.setSenderEmail("bob@example.com");
        msg1.setReceiverEmail("alice@example.com");
        msg1.setBody("Message from blocked user");
        msg1.setStatus("SENT");
        msg1.setTimestamp(java.time.LocalDateTime.now().minusDays(1));

        CasboxMessageRepository repo = (CasboxMessageRepository) Proxy.newProxyInstance(
                CasboxMessageRepository.class.getClassLoader(),
                new Class<?>[]{CasboxMessageRepository.class},
                (proxy, method, args) -> {
                    if ("findAllMessagesForUser".equals(method.getName())) {
                        return List.of(msg1);
                    }
                    return null;
                }
        );

        com.btctech.mailapp.repository.BlockedContactRepository blockedRepo =
                (com.btctech.mailapp.repository.BlockedContactRepository) Proxy.newProxyInstance(
                        com.btctech.mailapp.repository.BlockedContactRepository.class.getClassLoader(),
                        new Class<?>[]{com.btctech.mailapp.repository.BlockedContactRepository.class},
                        (proxy, method, args) -> {
                            if ("existsByUserEmailAndBlockedEmail".equals(method.getName())) {
                                String user = (String) args[0];
                                String blocked = (String) args[1];
                                return "alice@example.com".equals(user) && "bob@example.com".equals(blocked);
                            }
                            return false;
                        }
                );

        BlockedContactService blockedService = new BlockedContactService(blockedRepo, null);
        CasboxService service = new CasboxService(repo, null, null, null, null, null, blockedService);

        List<com.btctech.mailapp.dto.CasboxMessageDto> all = service.getAllMessages("alice@example.com");
        assertEquals(1, all.size(), "Historical messages from blocked user must be returned");
        assertEquals("bob@example.com", all.get(0).getSenderEmail());
    }

    @Test
    @DisplayName("sendMessage throws MailException when sender is blocked by recipient")
    void testSendMessageThrowsWhenSenderBlockedByRecipient() {
        CasboxMessageRepository repo = (CasboxMessageRepository) Proxy.newProxyInstance(
                CasboxMessageRepository.class.getClassLoader(),
                new Class<?>[]{CasboxMessageRepository.class},
                (proxy, method, args) -> null
        );

        com.btctech.mailapp.repository.BlockedContactRepository blockedRepo =
                (com.btctech.mailapp.repository.BlockedContactRepository) Proxy.newProxyInstance(
                        com.btctech.mailapp.repository.BlockedContactRepository.class.getClassLoader(),
                        new Class<?>[]{com.btctech.mailapp.repository.BlockedContactRepository.class},
                        (proxy, method, args) -> {
                            if ("existsByUserEmailAndBlockedEmail".equals(method.getName())) {
                                String user = (String) args[0];
                                String blocked = (String) args[1];
                                // User B (bob) blocked User A (alice)
                                return "bob@example.com".equals(user) && "alice@example.com".equals(blocked);
                            }
                            return false;
                        }
                );

        BlockedContactService blockedService = new BlockedContactService(blockedRepo, null);
        CasboxService service = new CasboxService(repo, null, null, null, null, null, blockedService);

        com.btctech.mailapp.dto.CasboxSendRequest req = new com.btctech.mailapp.dto.CasboxSendRequest();
        req.setReceiverEmail("bob@example.com");
        req.setSubject("Test");
        req.setBody("Hello");

        com.btctech.mailapp.exception.MailException ex = assertThrows(
                com.btctech.mailapp.exception.MailException.class,
                () -> service.sendMessage("alice@example.com", req)
        );
        assertEquals("You cannot send messages to this user.", ex.getMessage());
    }

    @Test
    @DisplayName("sendMessage throws MailException when sender has blocked recipient")
    void testSendMessageThrowsWhenRecipientBlockedBySender() {
        CasboxMessageRepository repo = (CasboxMessageRepository) Proxy.newProxyInstance(
                CasboxMessageRepository.class.getClassLoader(),
                new Class<?>[]{CasboxMessageRepository.class},
                (proxy, method, args) -> null
        );

        com.btctech.mailapp.repository.BlockedContactRepository blockedRepo =
                (com.btctech.mailapp.repository.BlockedContactRepository) Proxy.newProxyInstance(
                        com.btctech.mailapp.repository.BlockedContactRepository.class.getClassLoader(),
                        new Class<?>[]{com.btctech.mailapp.repository.BlockedContactRepository.class},
                        (proxy, method, args) -> {
                            if ("existsByUserEmailAndBlockedEmail".equals(method.getName())) {
                                String user = (String) args[0];
                                String blocked = (String) args[1];
                                // User A (alice) blocked User B (bob)
                                return "alice@example.com".equals(user) && "bob@example.com".equals(blocked);
                            }
                            return false;
                        }
                );

        BlockedContactService blockedService = new BlockedContactService(blockedRepo, null);
        CasboxService service = new CasboxService(repo, null, null, null, null, null, blockedService);

        com.btctech.mailapp.dto.CasboxSendRequest req = new com.btctech.mailapp.dto.CasboxSendRequest();
        req.setReceiverEmail("bob@example.com");
        req.setSubject("Test");
        req.setBody("Hello");

        com.btctech.mailapp.exception.MailException ex = assertThrows(
                com.btctech.mailapp.exception.MailException.class,
                () -> service.sendMessage("alice@example.com", req)
        );
        assertEquals("You cannot send messages to this user because you have blocked them.", ex.getMessage());
    }

    @Test
    @DisplayName("editMessage within 5 minutes succeeds, updates body, sets isEdited, preserves metadata")
    void testEditOwnMessageWithinFiveMinutesSuccess() {
        CasboxMessage msg = new CasboxMessage();
        msg.setId(101L);
        msg.setSenderEmail("alice@example.com");
        msg.setReceiverEmail("bob@example.com");
        msg.setBody("Original text");
        java.time.LocalDateTime originalTime = java.time.LocalDateTime.now().minusMinutes(2);
        msg.setTimestamp(originalTime);

        CasboxMessageRepository repo = (CasboxMessageRepository) Proxy.newProxyInstance(
                CasboxMessageRepository.class.getClassLoader(),
                new Class<?>[]{CasboxMessageRepository.class},
                (proxy, method, args) -> {
                    if ("findById".equals(method.getName())) {
                        return Optional.of(msg);
                    }
                    if ("save".equals(method.getName())) {
                        return args[0];
                    }
                    return null;
                }
        );

        CasboxService service = new CasboxService(repo, null);
        com.btctech.mailapp.dto.CasboxEditRequest request = new com.btctech.mailapp.dto.CasboxEditRequest("Corrected text");
        com.btctech.mailapp.dto.CasboxMessageDto result = service.editMessage(101L, request, "alice@example.com");

        assertNotNull(result);
        assertEquals("Corrected text", result.getBody());
        assertTrue(result.getIsEdited());
        assertTrue(result.getEdited());
        assertEquals(101L, result.getId());
        assertEquals("alice@example.com", result.getSenderEmail());
        assertEquals("bob@example.com", result.getReceiverEmail());
        assertEquals(originalTime, result.getTimestamp());
        assertEquals(originalTime, msg.getTimestamp()); // original creation timestamp not reset
    }

    @Test
    @DisplayName("editMessage older than 5 minutes throws MailException")
    void testEditMessageOlderThanFiveMinutesThrowsException() {
        CasboxMessage msg = new CasboxMessage();
        msg.setId(102L);
        msg.setSenderEmail("alice@example.com");
        msg.setReceiverEmail("bob@example.com");
        msg.setBody("Old message");
        msg.setTimestamp(java.time.LocalDateTime.now().minusMinutes(6)); // 6 minutes ago

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
        com.btctech.mailapp.dto.CasboxEditRequest request = new com.btctech.mailapp.dto.CasboxEditRequest("Attempted edit");

        com.btctech.mailapp.exception.MailException ex = assertThrows(
                com.btctech.mailapp.exception.MailException.class,
                () -> service.editMessage(102L, request, "alice@example.com")
        );
        assertEquals("Messages can only be edited within 5 minutes of sending.", ex.getMessage());
    }

    @Test
    @DisplayName("editMessage by another user throws AccessDeniedException")
    void testEditAnotherUserMessageThrowsAccessDenied() {
        CasboxMessage msg = new CasboxMessage();
        msg.setId(103L);
        msg.setSenderEmail("bob@example.com");
        msg.setReceiverEmail("alice@example.com");
        msg.setBody("Bob's message");
        msg.setTimestamp(java.time.LocalDateTime.now().minusMinutes(1));

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
        com.btctech.mailapp.dto.CasboxEditRequest request = new com.btctech.mailapp.dto.CasboxEditRequest("Malicious edit");

        assertThrows(
                AccessDeniedException.class,
                () -> service.editMessage(103L, request, "alice@example.com")
        );
    }
}
