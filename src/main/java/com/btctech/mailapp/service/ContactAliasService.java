package com.btctech.mailapp.service;

import com.btctech.mailapp.dto.ContactAliasDto;
import com.btctech.mailapp.entity.ContactAlias;
import com.btctech.mailapp.entity.User;
import com.btctech.mailapp.exception.MailException;
import com.btctech.mailapp.repository.ContactAliasRepository;
import com.btctech.mailapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContactAliasService {

    private final ContactAliasRepository contactAliasRepository;
    private final UserRepository userRepository;
    private final UserService userService;

    @Transactional
    public ContactAliasDto setAlias(String ownerIdentifier, String contactIdentifier, String customName) {
        if (ownerIdentifier == null || contactIdentifier == null) {
            throw new MailException("Owner and contact identifiers must not be null");
        }

        User owner = resolveUser(ownerIdentifier);
        if (owner == null) {
            throw new MailException("Owner user not found: " + ownerIdentifier);
        }

        User contact = resolveUser(contactIdentifier);
        if (contact == null) {
            throw new MailException("Contact user not found: " + contactIdentifier);
        }

        String trimmedName = customName != null ? customName.trim() : null;

        // If custom name is empty or null, remove the alias (restore original name)
        if (trimmedName == null || trimmedName.isEmpty()) {
            contactAliasRepository.deleteByOwnerUserIdAndContactUserId(owner.getId(), contact.getId());
            log.info("Deleted contact alias for owner {} on contact {}", owner.getId(), contact.getId());
            return ContactAliasDto.builder()
                    .ownerUserId(owner.getId())
                    .contactUserId(contact.getId())
                    .contactUsername(contact.getUsername())
                    .contactEmail(contact.getEmail())
                    .customName(null)
                    .build();
        }

        Optional<ContactAlias> existingOpt = contactAliasRepository.findByOwnerUserIdAndContactUserId(owner.getId(), contact.getId());
        ContactAlias alias;
        if (existingOpt.isPresent()) {
            alias = existingOpt.get();
            alias.setCustomName(trimmedName);
            alias.setUpdatedAt(LocalDateTime.now());
            log.info("Updating existing contact alias for owner {} on contact {} to {}", owner.getId(), contact.getId(), trimmedName);
        } else {
            alias = ContactAlias.builder()
                    .ownerUserId(owner.getId())
                    .contactUserId(contact.getId())
                    .customName(trimmedName)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            log.info("Creating new contact alias for owner {} on contact {} as {}", owner.getId(), contact.getId(), trimmedName);
        }

        ContactAlias saved = contactAliasRepository.save(alias);

        return ContactAliasDto.builder()
                .id(saved.getId())
                .ownerUserId(owner.getId())
                .contactUserId(contact.getId())
                .contactUsername(contact.getUsername())
                .contactEmail(contact.getEmail())
                .customName(saved.getCustomName())
                .createdAt(saved.getCreatedAt())
                .updatedAt(saved.getUpdatedAt())
                .build();
    }

    @Transactional(readOnly = true)
    public ContactAliasDto getAlias(String ownerIdentifier, String contactIdentifier) {
        User owner = resolveUser(ownerIdentifier);
        if (owner == null) return null;

        User contact = resolveUser(contactIdentifier);
        if (contact == null) return null;

        return contactAliasRepository.findByOwnerUserIdAndContactUserId(owner.getId(), contact.getId())
                .map(alias -> ContactAliasDto.builder()
                        .id(alias.getId())
                        .ownerUserId(owner.getId())
                        .contactUserId(contact.getId())
                        .contactUsername(contact.getUsername())
                        .contactEmail(contact.getEmail())
                        .customName(alias.getCustomName())
                        .createdAt(alias.getCreatedAt())
                        .updatedAt(alias.getUpdatedAt())
                        .build())
                .orElse(null);
    }

    @Transactional
    public void deleteAlias(String ownerIdentifier, String contactIdentifier) {
        User owner = resolveUser(ownerIdentifier);
        if (owner == null) return;

        User contact = resolveUser(contactIdentifier);
        if (contact == null) return;

        contactAliasRepository.deleteByOwnerUserIdAndContactUserId(owner.getId(), contact.getId());
        log.info("Deleted contact alias for owner {} on contact {}", owner.getId(), contact.getId());
    }

    @Transactional(readOnly = true)
    public List<ContactAliasDto> getAllAliases(String ownerIdentifier) {
        User owner = resolveUser(ownerIdentifier);
        if (owner == null) return Collections.emptyList();

        List<ContactAlias> aliases = contactAliasRepository.findByOwnerUserId(owner.getId());
        List<ContactAliasDto> dtos = new ArrayList<>();

        for (ContactAlias alias : aliases) {
            User contact = userRepository.findById(alias.getContactUserId()).orElse(null);
            dtos.add(ContactAliasDto.builder()
                    .id(alias.getId())
                    .ownerUserId(owner.getId())
                    .contactUserId(alias.getContactUserId())
                    .contactUsername(contact != null ? contact.getUsername() : null)
                    .contactEmail(contact != null ? contact.getEmail() : null)
                    .customName(alias.getCustomName())
                    .createdAt(alias.getCreatedAt())
                    .updatedAt(alias.getUpdatedAt())
                    .build());
        }

        return dtos;
    }

    @Transactional(readOnly = true)
    public Map<String, String> getAliasLookupMap(String ownerIdentifier) {
        Map<String, String> map = new HashMap<>();
        User owner = resolveUser(ownerIdentifier);
        if (owner == null) return map;

        List<ContactAlias> aliases = contactAliasRepository.findByOwnerUserId(owner.getId());
        for (ContactAlias alias : aliases) {
            if (alias.getCustomName() == null || alias.getCustomName().trim().isEmpty()) {
                continue;
            }
            String custom = alias.getCustomName().trim();
            map.put(String.valueOf(alias.getContactUserId()), custom);

            userRepository.findById(alias.getContactUserId()).ifPresent(contact -> {
                if (contact.getUsername() != null) {
                    map.put(contact.getUsername().toLowerCase(), custom);
                }
                if (contact.getEmail() != null) {
                    map.put(contact.getEmail().toLowerCase(), custom);
                }
            });
        }
        return map;
    }

    public User resolveUser(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            return null;
        }
        String clean = identifier.trim();

        // 1. Try numeric user ID
        if (clean.matches("\\d+")) {
            try {
                Optional<User> byId = userRepository.findById(Long.parseLong(clean));
                if (byId.isPresent()) {
                    return byId.get();
                }
            } catch (Exception ignored) {}
        }

        // 2. Try via userService (handles email or username)
        try {
            User user = userService.getUserByEmailOrUsername(clean);
            if (user != null) return user;
        } catch (Exception ignored) {}

        // 3. Try direct userRepository lookups
        Optional<User> byEmail = userRepository.findByEmail(clean);
        if (byEmail.isPresent()) return byEmail.get();

        Optional<User> byUsername = userRepository.findByUsername(clean);
        if (byUsername.isPresent()) return byUsername.get();

        // 4. If identifier has '@', try local part as username
        if (clean.contains("@")) {
            String local = clean.substring(0, clean.indexOf("@"));
            Optional<User> byLocal = userRepository.findByUsername(local);
            if (byLocal.isPresent()) return byLocal.get();
        }

        return null;
    }
}
