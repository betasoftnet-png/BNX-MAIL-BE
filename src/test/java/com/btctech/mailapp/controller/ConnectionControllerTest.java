package com.btctech.mailapp.controller;

import com.btctech.mailapp.dto.ConnectionDto;
import com.btctech.mailapp.service.ConnectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConnectionControllerTest {

    private MockMvc mockMvc;
    private FakeConnectionService fakeConnectionService;
    private final String currentUser = "user1@example.com";

    static class FakeConnectionService extends ConnectionService {
        public String lastUserIdentifier;
        public String lastConnectionIdentifier;
        public String lastNewStatus;
        public int getAcceptedCallCount = 0;
        public int updateStatusCallCount = 0;

        public FakeConnectionService() {
            super(null, null, null, null, null);
        }

        @Override
        public List<ConnectionDto> getAcceptedConnections(String userIdentifier) {
            this.lastUserIdentifier = userIdentifier;
            this.getAcceptedCallCount++;
            ConnectionDto dto = ConnectionDto.builder()
                    .id(1L)
                    .requesterId(10L)
                    .receiverId(20L)
                    .contactUserId(20L)
                    .contactUsername("rahul")
                    .contactDisplayName("Rahul")
                    .status("CONNECTED")
                    .createdAt(LocalDateTime.now())
                    .build();
            return List.of(dto);
        }

        @Override
        public ConnectionDto updateStatus(String userIdentifier, String connectionIdentifier, String newStatus) {
            this.lastUserIdentifier = userIdentifier;
            this.lastConnectionIdentifier = connectionIdentifier;
            this.lastNewStatus = newStatus;
            this.updateStatusCallCount++;
            return ConnectionDto.builder()
                    .id(Long.parseLong(connectionIdentifier))
                    .requesterId(10L)
                    .receiverId(20L)
                    .contactUserId(20L)
                    .contactUsername("rahul")
                    .contactDisplayName("Rahul")
                    .status(newStatus)
                    .updatedAt(LocalDateTime.now())
                    .build();
        }
    }

    @BeforeEach
    void setUp() {
        fakeConnectionService = new FakeConnectionService();
        ConnectionController controller = new ConnectionController(fakeConnectionService);
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
    @DisplayName("GET /api/connections/accepted returns list of accepted connections")
    void testGetAcceptedConnections() throws Exception {
        mockMvc.perform(get("/api/connections/accepted")
                .principal(new UsernamePasswordAuthenticationToken(currentUser, "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].contactUsername").value("rahul"))
                .andExpect(jsonPath("$[0].contactDisplayName").value("Rahul"))
                .andExpect(jsonPath("$[0].status").value("CONNECTED"));

        assertEquals(1, fakeConnectionService.getAcceptedCallCount);
        assertEquals(currentUser, fakeConnectionService.lastUserIdentifier);
    }

    @Test
    @DisplayName("PATCH /api/connections/{connectionId}/status updates status to DISCONNECTED")
    void testUpdateConnectionStatusToDisconnected() throws Exception {
        mockMvc.perform(patch("/api/connections/1/status")
                .principal(new UsernamePasswordAuthenticationToken(currentUser, "password"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"DISCONNECTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISCONNECTED"))
                .andExpect(jsonPath("$.contactDisplayName").value("Rahul"));

        assertEquals(1, fakeConnectionService.updateStatusCallCount);
        assertEquals(currentUser, fakeConnectionService.lastUserIdentifier);
        assertEquals("1", fakeConnectionService.lastConnectionIdentifier);
        assertEquals("DISCONNECTED", fakeConnectionService.lastNewStatus);
    }

    @Test
    @DisplayName("PATCH /api/connections/{connectionId}/status updates status to CONNECTED")
    void testUpdateConnectionStatusToConnected() throws Exception {
        mockMvc.perform(patch("/api/connections/1/status")
                .principal(new UsernamePasswordAuthenticationToken(currentUser, "password"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"CONNECTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONNECTED"));

        assertEquals(1, fakeConnectionService.updateStatusCallCount);
        assertEquals(currentUser, fakeConnectionService.lastUserIdentifier);
        assertEquals("1", fakeConnectionService.lastConnectionIdentifier);
        assertEquals("CONNECTED", fakeConnectionService.lastNewStatus);
    }
}
