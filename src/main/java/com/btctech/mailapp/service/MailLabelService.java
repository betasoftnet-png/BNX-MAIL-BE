package com.btctech.mailapp.service;

import com.btctech.mailapp.entity.MailLabel;
import com.btctech.mailapp.entity.MailLabelMapping;
import com.btctech.mailapp.exception.MailException;
import com.btctech.mailapp.repository.MailLabelMappingRepository;
import com.btctech.mailapp.repository.MailLabelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MailLabelService {

    private final MailLabelRepository labelRepository;
    private final MailLabelMappingRepository mappingRepository;
    private final com.btctech.mailapp.repository.StarredEmailRepository starredEmailRepository;

    public List<MailLabel> getLabels(String userEmail) {
        return labelRepository.findByUserEmail(userEmail);
    }

    @Transactional
    public MailLabel createLabel(String userEmail, String name, String colorHex, Long parentId) {
        if (parentId == null) {
            if (labelRepository.existsByUserEmailAndNameAndParentIdIsNull(userEmail, name)) {
                throw new MailException("Label with name '" + name + "' already exists");
            }
        } else {
            MailLabel parent = labelRepository.findById(parentId)
                    .orElseThrow(() -> new MailException("Parent label not found"));
            if (!parent.getUserEmail().equals(userEmail)) {
                throw new MailException("Unauthorized parent label");
            }
            if (labelRepository.existsByUserEmailAndNameAndParentId(userEmail, name, parentId)) {
                throw new MailException("Label with name '" + name + "' already exists under this parent");
            }
        }

        MailLabel label = MailLabel.builder()
                .userEmail(userEmail)
                .name(name)
                .colorHex(colorHex)
                .parentId(parentId)
                .build();

        return labelRepository.save(label);
    }

    @Transactional
    public MailLabel updateLabel(String userEmail, Long labelId, String name, String colorHex, Long parentId) {
        MailLabel label = labelRepository.findById(labelId)
                .orElseThrow(() -> new MailException("Label not found"));

        if (!label.getUserEmail().equals(userEmail)) {
            throw new MailException("You do not have permission to edit this label");
        }

        if (parentId != null) {
            if (parentId.equals(labelId)) {
                throw new MailException("A label cannot be its own parent");
            }
            if (isDescendant(parentId, labelId)) {
                throw new MailException("Circular parent-child relationship is not allowed");
            }
            MailLabel parent = labelRepository.findById(parentId)
                    .orElseThrow(() -> new MailException("Parent label not found"));
            if (!parent.getUserEmail().equals(userEmail)) {
                throw new MailException("Unauthorized parent label");
            }
        }

        boolean parentChanged = (parentId == null && label.getParentId() != null) ||
                (parentId != null && !parentId.equals(label.getParentId()));
        boolean nameChanged = !label.getName().equals(name);

        if (nameChanged || parentChanged) {
            boolean duplicate = (parentId == null)
                    ? labelRepository.existsByUserEmailAndNameAndParentIdIsNull(userEmail, name)
                    : labelRepository.existsByUserEmailAndNameAndParentId(userEmail, name, parentId);
            if (duplicate) {
                throw new MailException("Label with name '" + name + "' already exists");
            }
        }

        label.setName(name);
        label.setColorHex(colorHex);
        label.setParentId(parentId);

        return labelRepository.save(label);
    }

    private boolean isDescendant(Long targetParentId, Long currentLabelId) {
        Long current = targetParentId;
        int depth = 0;
        while (current != null && depth < 100) {
            if (current.equals(currentLabelId)) {
                return true;
            }
            MailLabel lbl = labelRepository.findById(current).orElse(null);
            if (lbl == null) {
                break;
            }
            current = lbl.getParentId();
            depth++;
        }
        return false;
    }

    @Transactional
    public void deleteLabel(String userEmail, Long labelId) {
        MailLabel label = labelRepository.findById(labelId)
                .orElseThrow(() -> new MailException("Label not found"));

        if (!label.getUserEmail().equals(userEmail)) {
            throw new MailException("You do not have permission to delete this label");
        }

        // Recursively delete children
        deleteChildren(userEmail, labelId);

        // Clean up mappings first
        mappingRepository.deleteByLabelId(labelId);
        labelRepository.delete(label);
    }

    private void deleteChildren(String userEmail, Long parentId) {
        List<MailLabel> children = labelRepository.findByParentId(parentId);
        for (MailLabel child : children) {
            // Recursively delete its children
            deleteChildren(userEmail, child.getId());
            // Delete mappings and the child label
            mappingRepository.deleteByLabelId(child.getId());
            labelRepository.delete(child);
        }
    }

    private String normalizeFolder(String userEmail, String emailUid, String folderName) {
        if (folderName == null) return "INBOX";
        String upper = folderName.toUpperCase();
        if (upper.contains("STARRED")) {
            var list = starredEmailRepository.findByUserEmailAndUid(userEmail, emailUid);
            if (!list.isEmpty() && !list.get(0).getFolderName().toUpperCase().contains("STARRED")) {
                return list.get(0).getFolderName();
            }
            return "INBOX";
        }
        if (upper.contains("ALLMAIL") || upper.contains("ALL-MAIL") || upper.contains("LABEL")) {
            var list = mappingRepository.findByUserEmailAndEmailUid(userEmail, emailUid);
            if (!list.isEmpty() && !list.get(0).getFolderName().toUpperCase().contains("LABEL") && !list.get(0).getFolderName().toUpperCase().contains("ALL")) {
                return list.get(0).getFolderName();
            }
            return "INBOX";
        }
        if (upper.contains("INBOX")) return "INBOX";
        if (upper.contains("SENT")) return "Sent";
        if (upper.contains("TRASH") || upper.contains("DELETED")) return "Trash";
        if (upper.contains("SPAM") || upper.contains("JUNK")) return "Spam";
        if (upper.contains("SNOOZED")) return "Snoozed";
        if (upper.contains("ARCHIVE")) return "Archive";
        return folderName;
    }

    @Transactional
    public void applyLabelToEmail(String userEmail, String emailUid, String folderName, Long labelId) {
        MailLabel label = labelRepository.findById(labelId)
                .orElseThrow(() -> new MailException("Label not found"));

        if (!label.getUserEmail().equals(userEmail)) {
            throw new MailException("Unauthorized label application");
        }

        String normFolder = normalizeFolder(userEmail, emailUid, folderName);

        // Check if already applied ignoring folder name to prevent duplicates
        List<MailLabelMapping> existing = mappingRepository.findByUserEmailAndEmailUidAndLabelId(userEmail, emailUid, labelId);
        if (!existing.isEmpty()) {
            return;
        }

        MailLabelMapping mapping = MailLabelMapping.builder()
                .userEmail(userEmail)
                .emailUid(emailUid)
                .folderName(normFolder)
                .label(label)
                .build();

        mappingRepository.save(mapping);
    }

    @Transactional
    public void removeLabelFromEmail(String userEmail, String emailUid, String folderName, Long labelId) {
        List<MailLabelMapping> mappings = mappingRepository.findByUserEmailAndEmailUidAndLabelId(userEmail, emailUid, labelId);
        mappingRepository.deleteAll(mappings);
    }

    public List<MailLabel> getLabelsForEmail(String userEmail, String emailUid, String folderName) {
        return mappingRepository.findByUserEmailAndEmailUid(userEmail, emailUid)
                .stream()
                .map(MailLabelMapping::getLabel)
                .distinct()
                .collect(Collectors.toList());
    }
}
