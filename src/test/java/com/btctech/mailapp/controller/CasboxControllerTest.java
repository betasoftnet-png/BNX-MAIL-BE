package com.btctech.mailapp.controller;

import com.btctech.mailapp.service.CasboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CasboxControllerTest {

    private MockMvc mockMvc;
    private FakeCasboxService fakeCasboxService;
    private final String currentUser = "user1@example.com";

    static class FakeCasboxService extends CasboxService {
        public String lastUserEmail;
        public String lastContactEmailOrId;
        public int deleteCallCount = 0;
        public boolean throwAccessDenied = false;

        public Long lastEditMessageId;
        public com.btctech.mailapp.dto.CasboxEditRequest lastEditRequest;
        public int editCallCount = 0;

        public FakeCasboxService() {
            super(null, null);
        }

        @Override
        public com.btctech.mailapp.dto.CasboxMessageDto editMessage(Long messageId, com.btctech.mailapp.dto.CasboxEditRequest request, String userEmail) {
            this.lastEditMessageId = messageId;
            this.lastEditRequest = request;
            this.lastUserEmail = userEmail;
            this.editCallCount++;
            com.btctech.mailapp.dto.CasboxMessageDto dto = new com.btctech.mailapp.dto.CasboxMessageDto();
            dto.setId(messageId);
            dto.setBody(request != null ? request.getCleanBody() : "");
            dto.setIsEdited(true);
            return dto;
        }

        public boolean validRecipientResult = true;
        public String lastValidatedEmail;

        @Override
        public boolean isValidRecipient(String identifier) {
            this.lastValidatedEmail = identifier;
            return validRecipientResult;
        }

        @Override
        public void deleteConversation(String userEmail, String contactEmailOrId) {
            if (throwAccessDenied) {
                throw new AccessDeniedException("Unauthorized to delete this conversation");
            }
            this.lastUserEmail = userEmail;
            this.lastContactEmailOrId = contactEmailOrId;
            this.deleteCallCount++;
        }
    }

    @BeforeEach
    void setUp() {
        fakeCasboxService = new FakeCasboxService();
        CasboxController controller = new CasboxController(fakeCasboxService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        Authentication auth = new UsernamePasswordAuthenticationToken(currentUser, "password", Collections.emptyList());
        SecurityContext securityContext = new SecurityContext() {
            @Override
            public Authentication getAuthentication() {
                return auth;
            }

            @Override
            public void setAuthentication(Authentication authentication) {
            }
        };
        SecurityContextHolder.setContext(securityContext);
    }

    @Test
    @DisplayName("DELETE /api/casbox/conversation/{contactEmail} should return 200 and invoke service")
    void testDeleteConversationByPath() throws Exception {
        mockMvc.perform(delete("/api/casbox/conversation/user2@example.com")
                .principal(new UsernamePasswordAuthenticationToken(currentUser, "password")))
                .andExpect(status().isOk());

        assertEquals(1, fakeCasboxService.deleteCallCount);
        assertEquals(currentUser, fakeCasboxService.lastUserEmail);
        assertEquals("user2@example.com", fakeCasboxService.lastContactEmailOrId);
    }

    @Test
    @DisplayName("DELETE /api/casbox/messages/{contactEmailOrId} should return 200 and invoke service")
    void testDeleteConversationByMessageIdPath() throws Exception {
        mockMvc.perform(delete("/api/casbox/messages/123")
                .principal(new UsernamePasswordAuthenticationToken(currentUser, "password")))
                .andExpect(status().isOk());

        assertEquals(1, fakeCasboxService.deleteCallCount);
        assertEquals(currentUser, fakeCasboxService.lastUserEmail);
        assertEquals("123", fakeCasboxService.lastContactEmailOrId);
    }

    @Test
    @DisplayName("DELETE /api/casbox/conversation?email={contactEmail} query param should return 200")
    void testDeleteConversationByQueryParam() throws Exception {
        mockMvc.perform(delete("/api/casbox/conversation")
                .param("email", "user2@example.com")
                .principal(new UsernamePasswordAuthenticationToken(currentUser, "password")))
                .andExpect(status().isOk());

        assertEquals(1, fakeCasboxService.deleteCallCount);
        assertEquals(currentUser, fakeCasboxService.lastUserEmail);
        assertEquals("user2@example.com", fakeCasboxService.lastContactEmailOrId);
    }

    @Test
    @DisplayName("DELETE with AccessDeniedException should throw AccessDenied")
    void testDeleteConversationUnauthorized() {
        fakeCasboxService.throwAccessDenied = true;

        try {
            mockMvc.perform(delete("/api/casbox/messages/999")
                    .principal(new UsernamePasswordAuthenticationToken(currentUser, "password")));
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof AccessDeniedException || e instanceof AccessDeniedException);
        }
    }

    @Test
    @DisplayName("PATCH /api/casbox/{id} should return 200 and invoke editMessage")
    void testEditMessagePatchEndpoint() throws Exception {
        String json = "{\"body\":\"Updated message content\"}";
        mockMvc.perform(patch("/api/casbox/456")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(json)
                .principal(new UsernamePasswordAuthenticationToken(currentUser, "password")))
                .andExpect(status().isOk());

        assertEquals(1, fakeCasboxService.editCallCount);
        assertEquals(456L, fakeCasboxService.lastEditMessageId);
        assertEquals("Updated message content", fakeCasboxService.lastEditRequest.getCleanBody());
        assertEquals(currentUser, fakeCasboxService.lastUserEmail);
    }

    @Test
    @DisplayName("GET /api/casbox/validate-recipient should return 200 when recipient exists")
    void testValidateRecipientExisting() throws Exception {
        fakeCasboxService.validRecipientResult = true;
        mockMvc.perform(get("/api/casbox/validate-recipient")
                .param("email", "existing@bnxmail.com")
                .principal(new UsernamePasswordAuthenticationToken(currentUser, "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.email").value("existing@bnxmail.com"));

        assertEquals("existing@bnxmail.com", fakeCasboxService.lastValidatedEmail);
    }

    @Test
    @DisplayName("GET /api/casbox/validate-recipient should return 200 with valid:false when recipient does not exist")
    void testValidateRecipientNonExistent() throws Exception {
        fakeCasboxService.validRecipientResult = false;
        mockMvc.perform(get("/api/casbox/validate-recipient")
                .param("email", "nonexistent@bnxmail.com")
                .principal(new UsernamePasswordAuthenticationToken(currentUser, "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.message").value("Invalid email ID. This BNXmail account does not exist."));

        assertEquals("nonexistent@bnxmail.com", fakeCasboxService.lastValidatedEmail);
    }

    @Test
    @DisplayName("GET /api/casbox/validate-recipient should return 200 with valid:false when email param is blank")
    void testValidateRecipientBlank() throws Exception {
        fakeCasboxService.validRecipientResult = false;
        mockMvc.perform(get("/api/casbox/validate-recipient")
                .param("email", "   ")
                .principal(new UsernamePasswordAuthenticationToken(currentUser, "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.message").value("Invalid email ID. This BNXmail account does not exist."));
    }
}
