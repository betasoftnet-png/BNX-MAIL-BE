package com.btctech.mailapp.controller;

import com.btctech.mailapp.dto.ConnectionDto;
import com.btctech.mailapp.dto.ConnectionStatusUpdateRequest;
import com.btctech.mailapp.service.ConnectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/connections")
@RequiredArgsConstructor
public class ConnectionController {

    private final ConnectionService connectionService;

    /**
     * GET /api/connections/accepted
     * Returns the current logged-in user's accepted connections list.
     */
    @GetMapping("/accepted")
    public ResponseEntity<List<ConnectionDto>> getAcceptedConnections(Authentication authentication) {
        String userIdentifier = authentication.getName();
        List<ConnectionDto> connections = connectionService.getAcceptedConnections(userIdentifier);
        return ResponseEntity.ok(connections);
    }

    /**
     * GET /api/connections
     * Fallback alias for returning accepted connections.
     */
    @GetMapping
    public ResponseEntity<List<ConnectionDto>> getAllConnections(Authentication authentication) {
        String userIdentifier = authentication.getName();
        return ResponseEntity.ok(connectionService.getAcceptedConnections(userIdentifier));
    }

    /**
     * PATCH /api/connections/{connectionId}/status
     * Update connection status to CONNECTED or DISCONNECTED.
     */
    @PatchMapping("/{connectionId}/status")
    public ResponseEntity<ConnectionDto> updateConnectionStatus(
            @PathVariable String connectionId,
            @Valid @RequestBody ConnectionStatusUpdateRequest request,
            Authentication authentication) {
        String userIdentifier = authentication.getName();
        ConnectionDto updated = connectionService.updateStatus(userIdentifier, connectionId, request.getStatus());
        return ResponseEntity.ok(updated);
    }

    /**
     * PUT /api/connections/{connectionId}/status
     * Support PUT for clients that do not support PATCH.
     */
    @PutMapping("/{connectionId}/status")
    public ResponseEntity<ConnectionDto> putConnectionStatus(
            @PathVariable String connectionId,
            @Valid @RequestBody ConnectionStatusUpdateRequest request,
            Authentication authentication) {
        String userIdentifier = authentication.getName();
        ConnectionDto updated = connectionService.updateStatus(userIdentifier, connectionId, request.getStatus());
        return ResponseEntity.ok(updated);
    }
}
