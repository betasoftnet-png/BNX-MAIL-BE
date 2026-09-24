package com.btctech.mailapp.controller;

import com.btctech.mailapp.dto.ContactAliasDto;
import com.btctech.mailapp.service.ContactAliasService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ContactAliasControllerTest {

    private MockMvc mockMvc;
    private ContactAliasService contactAliasService;
    private final String currentUser = "user101@example.com";

    @BeforeEach
    void setUp() {
        contactAliasService = Mockito.mock(ContactAliasService.class);
        ContactAliasController controller = new ContactAliasController(contactAliasService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        Authentication auth = new UsernamePasswordAuthenticationToken(currentUser, "password", Collections.emptyList());
        SecurityContext securityContext = Mockito.mock(SecurityContext.class);
        when(securityContext.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(securityContext);
    }

    @Test
    @DisplayName("PUT /api/contact-aliases/{contactUserId} should update and return alias")
    void testSetAlias() throws Exception {
        ContactAliasDto expectedDto = ContactAliasDto.builder()
                .id(1L)
                .ownerUserId(101L)
                .contactUserId(205L)
                .contactUsername("rahulram042")
                .customName("Rahul")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(contactAliasService.setAlias(eq(currentUser), eq("205"), eq("Rahul"))).thenReturn(expectedDto);

        mockMvc.perform(put("/api/contact-aliases/205")
                .principal(new UsernamePasswordAuthenticationToken(currentUser, "password"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customName\": \"Rahul\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customName").value("Rahul"))
                .andExpect(jsonPath("$.contactUserId").value(205))
                .andExpect(jsonPath("$.contactUsername").value("rahulram042"));

        verify(contactAliasService).setAlias(eq(currentUser), eq("205"), eq("Rahul"));
    }

    @Test
    @DisplayName("GET /api/contact-aliases/{contactUserId} should return alias")
    void testGetAlias() throws Exception {
        ContactAliasDto expectedDto = ContactAliasDto.builder()
                .id(1L)
                .ownerUserId(101L)
                .contactUserId(205L)
                .contactUsername("rahulram042")
                .customName("Rahul")
                .build();

        when(contactAliasService.getAlias(eq(currentUser), eq("205"))).thenReturn(expectedDto);

        mockMvc.perform(get("/api/contact-aliases/205")
                .principal(new UsernamePasswordAuthenticationToken(currentUser, "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customName").value("Rahul"))
                .andExpect(jsonPath("$.contactUserId").value(205));
    }

    @Test
    @DisplayName("DELETE /api/contact-aliases/{contactUserId} should delete alias")
    void testDeleteAlias() throws Exception {
        mockMvc.perform(delete("/api/contact-aliases/205")
                .principal(new UsernamePasswordAuthenticationToken(currentUser, "password")))
                .andExpect(status().isOk());

        verify(contactAliasService).deleteAlias(eq(currentUser), eq("205"));
    }

    @Test
    @DisplayName("GET /api/contact-aliases should return list of aliases")
    void testGetAllAliases() throws Exception {
        ContactAliasDto alias = ContactAliasDto.builder()
                .id(1L)
                .ownerUserId(101L)
                .contactUserId(205L)
                .contactUsername("rahulram042")
                .customName("Rahul")
                .build();

        when(contactAliasService.getAllAliases(eq(currentUser))).thenReturn(List.of(alias));

        mockMvc.perform(get("/api/contact-aliases")
                .principal(new UsernamePasswordAuthenticationToken(currentUser, "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].customName").value("Rahul"));
    }
}
