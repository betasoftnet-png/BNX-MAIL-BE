package com.btctech.mailapp.service;

import com.btctech.mailapp.config.DatabaseTableInitializer;
import com.btctech.mailapp.dto.ConnectionDto;
import com.btctech.mailapp.entity.Connection;
import com.btctech.mailapp.entity.ContactAlias;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.exception.MailException;
import com.btctech.mailapp.repository.CasboxMessageRepository;
import com.btctech.mailapp.repository.ConnectionRepository;
import com.btctech.mailapp.repository.ContactAliasRepository;
import com.btctech.mailapp.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class ConnectionService {

    private final ConnectionRepository connectionRepository;
    private final UserRepository userRepository;
    private final ContactAliasRepository contactAliasRepository;
    private final UserService userService;
    private final CasboxMessageRepository casboxMessageRepository;
    private final DatabaseTableInitializer databaseTableInitializer;

    @Autowired
    public ConnectionService(ConnectionRepository connectionRepository,
                             UserRepository userRepository,
                             ContactAliasRepository contactAliasRepository,
                             UserService userService,
                             CasboxMessageRepository casboxMessageRepository,
                             @Lazy DatabaseTableInitializer databaseTableInitializer) {
        this.connectionRepository = connectionRepository;
        this.userRepository = userRepository;
        this.contactAliasRepository = contactAliasRepository;
        this.userService = userService;
        this.casboxMessageRepository = casboxMessageRepository;
        this.databaseTableInitializer = databaseTableInitializer;
    }

    public ConnectionService(ConnectionRepository connectionRepository,
                             UserRepository userRepository,
                             ContactAliasRepository contactAliasRepository,
                             UserService userService,
                             CasboxMessageRepository casboxMessageRepository) {
        this(connectionRepository, userRepository, contactAliasRepository, userService, casboxMessageRepository, null);
    }

    @Transactional
    public List<ConnectionDto> getAcceptedConnections(String userIdentifier) {
        try {
            User user = resolveUser(userIdentifier);
            if (user == null) {
                return Collections.emptyList();
            }

            // 1. Fetch all accepted/connected/disconnected connections from DB
            List<Connection> connections = new ArrayList<>();
            try {
                connections = new ArrayList<>(connectionRepository.findAcceptedConnectionsForUser(user.getId()));
            } catch (Exception e) {
                log.warn("Could not query connections table (attempting auto-creation): {}", e.getMessage());
                if (databaseTableInitializer != null) {
                    databaseTableInitializer.ensureTablesExist();
                    try {
                        connections = new ArrayList<>(connectionRepository.findAcceptedConnectionsForUser(user.getId()));
                    } catch (Exception ignored) {}
                }
            }

            Set<Long> existingContactUserIds = new HashSet<>();
            for (Connection c : connections) {
                Long otherId = c.getRequesterId().equals(user.getId()) ? c.getReceiverId() : c.getRequesterId();
                existingContactUserIds.add(otherId);
            }

            // 2. Auto-sync existing Casbox chat contacts if any don't have a Connection row yet
            try {
                List<com.btctech.mailapp.entity.CasboxMessage> userMessages = casboxMessageRepository.findAllMessagesForUser(user.getEmail());
                for (com.btctech.mailapp.entity.CasboxMessage msg : userMessages) {
                    String otherEmail = user.getEmail() != null && user.getEmail().equalsIgnoreCase(msg.getSenderEmail())
                            ? msg.getReceiverEmail() : msg.getSenderEmail();
                    if (otherEmail != null && !otherEmail.equalsIgnoreCase(user.getEmail())) {
                        User contactUser = resolveUser(otherEmail);
                        if (contactUser != null && !contactUser.getId().equals(user.getId()) && !existingContactUserIds.contains(contactUser.getId())) {
                            // Check if connection already exists between them
                            Optional<Connection> existingOpt = Optional.empty();
                            try {
                                existingOpt = connectionRepository.findConnectionBetweenUsers(user.getId(), contactUser.getId());
                            } catch (Exception ignored) {}

                            if (existingOpt.isEmpty()) {
                                Connection newConn = Connection.builder()
                                        .requesterId(user.getId())
                                        .receiverId(contactUser.getId())
                                        .status("CONNECTED")
                                        .createdAt(LocalDateTime.now())
                                        .updatedAt(LocalDateTime.now())
                                        .build();
                                try {
                                    Connection saved = connectionRepository.save(newConn);
                                    connections.add(saved);
                                    existingContactUserIds.add(contactUser.getId());
                                    log.info("Auto-provisioned CONNECTED connection between user {} and {}", user.getId(), contactUser.getId());
                                } catch (Exception e) {
                                    log.warn("Could not save auto-provisioned connection: {}", e.getMessage());
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Error auto-syncing existing chat contacts to connections: {}", e.getMessage());
            }

            // 3. Map to DTOs
            List<ConnectionDto> dtos = new ArrayList<>();
            for (Connection c : connections) {
                Long contactUserId = c.getRequesterId().equals(user.getId()) ? c.getReceiverId() : c.getRequesterId();
                User contactUser = userRepository.findById(contactUserId).orElse(null);
                if (contactUser == null) continue;

                String customName = null;
                try {
                    customName = contactAliasRepository.findByOwnerUserIdAndContactUserId(user.getId(), contactUserId)
                            .map(ContactAlias::getCustomName)
                            .filter(name -> name != null && !name.trim().isEmpty())
                            .orElse(null);
                } catch (Exception ignored) {}

                String displayName = customName != null ? customName
                        : (contactUser.getDisplayName() != null && !contactUser.getDisplayName().trim().isEmpty() ? contactUser.getDisplayName() : contactUser.getUsername());

                String status = c.getStatus() != null ? c.getStatus().toUpperCase() : "CONNECTED";
                if ("ACCEPTED".equals(status)) {
                    status = "CONNECTED";
                }

                dtos.add(ConnectionDto.builder()
                        .id(c.getId())
                        .requesterId(c.getRequesterId())
                        .receiverId(c.getReceiverId())
                        .contactUserId(contactUserId)
                        .contactUsername(contactUser.getUsername())
                        .contactEmail(contactUser.getEmail())
                        .contactDisplayName(displayName)
                        .contactProfilePicture(contactUser.getProfilePicture())
                        .status(status)
                        .createdAt(c.getCreatedAt())
                        .updatedAt(c.getUpdatedAt())
                        .build());
            }

            // Sort: CONNECTED first, then by contactDisplayName
            dtos.sort((a, b) -> {
                if (a.getStatus().equals(b.getStatus())) {
                    return String.valueOf(a.getContactDisplayName()).compareToIgnoreCase(String.valueOf(b.getContactDisplayName()));
                }
                return "CONNECTED".equals(a.getStatus()) ? -1 : 1;
            });

            return dtos;
        } catch (Exception e) {
            log.warn("Error in getAcceptedConnections for {}: {}", userIdentifier, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Transactional
    public ConnectionDto updateStatus(String userIdentifier, String connectionIdentifier, String newStatus) {
        User user = resolveUser(userIdentifier);
        if (user == null) {
            throw new MailException("User not found: " + userIdentifier);
        }

        Connection connection = null;

        // Try numeric connection ID
        if (connectionIdentifier != null && connectionIdentifier.matches("\\d+")) {
            try {
                connection = connectionRepository.findById(Long.parseLong(connectionIdentifier)).orElse(null);
            } catch (Exception ignored) {}
        }

        // If not found by ID, try finding by contact user identifier
        if (connection == null && connectionIdentifier != null) {
            User contactUser = resolveUser(connectionIdentifier);
            if (contactUser != null) {
                try {
                    connection = connectionRepository.findConnectionBetweenUsers(user.getId(), contactUser.getId()).orElse(null);
                } catch (Exception ignored) {}

                if (connection == null) {
                    connection = Connection.builder()
                            .requesterId(user.getId())
                            .receiverId(contactUser.getId())
                            .status("CONNECTED")
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();
                }
            }
        }

        if (connection == null) {
            throw new MailException("Connection not found: " + connectionIdentifier);
        }

        // Verify security: logged-in user must be either requester or receiver
        if (connection.getId() != null) {
            if (!connection.getRequesterId().equals(user.getId()) && !connection.getReceiverId().equals(user.getId())) {
                log.warn("Access denied: User {} tried to modify unrelated connection {}", user.getId(), connection.getId());
                throw new AccessDeniedException("Unauthorized to modify this connection");
            }
        }

        String normalizedStatus = "DISCONNECTED".equalsIgnoreCase(newStatus) ? "DISCONNECTED" : "CONNECTED";
        connection.setStatus(normalizedStatus);
        connection.setUpdatedAt(LocalDateTime.now());

        Connection saved;
        try {
            saved = connectionRepository.save(connection);
        } catch (Exception e) {
            log.warn("Failed saving connection, ensuring tables exist: {}", e.getMessage());
            if (databaseTableInitializer != null) {
                databaseTableInitializer.ensureTablesExist();
                saved = connectionRepository.save(connection);
            } else {
                throw new MailException("Failed to update connection status: " + e.getMessage());
            }
        }

        Long contactUserId = saved.getRequesterId().equals(user.getId()) ? saved.getReceiverId() : saved.getRequesterId();
        User contactUser = userRepository.findById(contactUserId).orElse(null);

        String customName = null;
        try {
            customName = contactAliasRepository.findByOwnerUserIdAndContactUserId(user.getId(), contactUserId)
                    .map(ContactAlias::getCustomName)
                    .filter(name -> name != null && !name.trim().isEmpty())
                    .orElse(null);
        } catch (Exception ignored) {}

        String displayName = customName != null ? customName
                : (contactUser != null && contactUser.getDisplayName() != null ? contactUser.getDisplayName()
                : (contactUser != null ? contactUser.getUsername() : ""));

        log.info("Connection {} status updated to {} by user {}", saved.getId(), normalizedStatus, user.getId());

        return ConnectionDto.builder()
                .id(saved.getId())
                .requesterId(saved.getRequesterId())
                .receiverId(saved.getReceiverId())
                .contactUserId(contactUserId)
                .contactUsername(contactUser != null ? contactUser.getUsername() : null)
                .contactEmail(contactUser != null ? contactUser.getEmail() : null)
                .contactDisplayName(displayName)
                .contactProfilePicture(contactUser != null ? contactUser.getProfilePicture() : null)
                .status(normalizedStatus)
                .createdAt(saved.getCreatedAt())
                .updatedAt(saved.getUpdatedAt())
                .build();
    }

    @Transactional(readOnly = true)
    public boolean isConnectionActive(Long user1Id, Long user2Id) {
        if (user1Id == null || user2Id == null) {
            return true;
        }
        try {
            Optional<Connection> connOpt = connectionRepository.findConnectionBetweenUsers(user1Id, user2Id);
            if (connOpt.isPresent()) {
                Connection conn = connOpt.get();
                if ("DISCONNECTED".equalsIgnoreCase(conn.getStatus())) {
                    return false;
                }
            }
        } catch (Exception e) {
            log.warn("Could not check isConnectionActive: {}", e.getMessage());
        }
        return true;
    }

    @Transactional(readOnly = true)
    public Set<String> getDisconnectedContactIdentifiers(Long userId) {
        Set<String> set = new HashSet<>();
        if (userId == null) return set;

        try {
            List<Connection> connections = connectionRepository.findAcceptedConnectionsForUser(userId);
            for (Connection c : connections) {
                if ("DISCONNECTED".equalsIgnoreCase(c.getStatus())) {
                    Long otherId = c.getRequesterId().equals(userId) ? c.getReceiverId() : c.getRequesterId();
                    set.add(String.valueOf(otherId));
                    userRepository.findById(otherId).ifPresent(other -> {
                        if (other.getUsername() != null) set.add(other.getUsername().toLowerCase());
                        if (other.getEmail() != null) set.add(other.getEmail().toLowerCase());
                    });
                }
            }
        } catch (Exception e) {
            log.warn("Could not get disconnected contact identifiers: {}", e.getMessage());
        }
        return set;
    }

    public User resolveUser(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            return null;
        }
        String clean = identifier.trim();

        if (clean.matches("\\d+")) {
            try {
                Optional<User> byId = userRepository.findById(Long.parseLong(clean));
                if (byId.isPresent()) return byId.get();
            } catch (Exception ignored) {}
        }

        try {
            User user = userService.getUserByEmailOrUsername(clean);
            if (user != null) return user;
        } catch (Exception ignored) {}

        Optional<User> byEmail = userRepository.findByEmail(clean);
        if (byEmail.isPresent()) return byEmail.get();

        Optional<User> byUsername = userRepository.findByUsername(clean);
        if (byUsername.isPresent()) return byUsername.get();

        if (clean.contains("@")) {
            String local = clean.substring(0, clean.indexOf("@"));
            Optional<User> byLocal = userRepository.findByUsername(local);
            if (byLocal.isPresent()) return byLocal.get();
        }

        return null;
    }
}
