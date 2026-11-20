package com.btctech.mailapp.service;

import com.btctech.mailapp.entity.MailLabel;
import com.btctech.mailapp.entity.MailLabelMapping;
import com.btctech.mailapp.exception.MailException;
import com.btctech.mailapp.repository.MailLabelMappingRepository;
import com.btctech.mailapp.repository.MailLabelRepository;
import com.btctech.mailapp.repository.StarredEmailRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MailLabelServiceTest {

    private MailLabelRepository labelRepository;
    private MailLabelMappingRepository mappingRepository;
    private StarredEmailRepository starredEmailRepository;
    private MailLabelService labelService;

    private final String USER_EMAIL = "user@bnx.com";

    @BeforeEach
    void setUp() {
        labelRepository = mock(MailLabelRepository.class);
        mappingRepository = mock(MailLabelMappingRepository.class);
        starredEmailRepository = mock(StarredEmailRepository.class);

        labelService = new MailLabelService(
                labelRepository,
                mappingRepository,
                starredEmailRepository
        );
    }

    @Test
    @DisplayName("1. Create top-level parent label successfully")
    void testCreateParentLabel() {
        when(labelRepository.existsByUserEmailAndNameAndParentIdIsNull(USER_EMAIL, "Work")).thenReturn(false);
        when(labelRepository.save(any(MailLabel.class))).thenAnswer(invocation -> {
            MailLabel lbl = invocation.getArgument(0);
            lbl.setId(1L);
            return lbl;
        });

        MailLabel created = labelService.createLabel(USER_EMAIL, "Work", "#4285F4", null);

        assertNotNull(created);
        assertEquals(1L, created.getId());
        assertEquals("Work", created.getName());
        assertNull(created.getParentId());
        verify(labelRepository, times(1)).save(any(MailLabel.class));
    }

    @Test
    @DisplayName("2. Create nested child label under existing parent label")
    void testCreateChildLabelUnderParent() {
        MailLabel parent = MailLabel.builder().id(1L).userEmail(USER_EMAIL).name("Work").colorHex("#4285F4").build();
        when(labelRepository.findById(1L)).thenReturn(Optional.of(parent));
        when(labelRepository.existsByUserEmailAndNameAndParentId(USER_EMAIL, "Projects", 1L)).thenReturn(false);
        when(labelRepository.save(any(MailLabel.class))).thenAnswer(invocation -> {
            MailLabel lbl = invocation.getArgument(0);
            lbl.setId(2L);
            return lbl;
        });

        MailLabel child = labelService.createLabel(USER_EMAIL, "Projects", "#34A853", 1L);

        assertNotNull(child);
        assertEquals(2L, child.getId());
        assertEquals("Projects", child.getName());
        assertEquals(1L, child.getParentId());
    }

    @Test
    @DisplayName("3. Prevent creating duplicate child label with same name under same parent")
    void testPreventDuplicateChildUnderSameParent() {
        MailLabel parent = MailLabel.builder().id(1L).userEmail(USER_EMAIL).name("Work").colorHex("#4285F4").build();
        when(labelRepository.findById(1L)).thenReturn(Optional.of(parent));
        when(labelRepository.existsByUserEmailAndNameAndParentId(USER_EMAIL, "Projects", 1L)).thenReturn(true);

        assertThrows(MailException.class, () -> {
            labelService.createLabel(USER_EMAIL, "Projects", "#34A853", 1L);
        });
    }

    @Test
    @DisplayName("4. Allow same child label name under different parents (Work/Projects vs Personal/Projects)")
    void testAllowSameChildNameUnderDifferentParents() {
        MailLabel personalParent = MailLabel.builder().id(2L).userEmail(USER_EMAIL).name("Personal").colorHex("#EA4335").build();
        when(labelRepository.findById(2L)).thenReturn(Optional.of(personalParent));
        when(labelRepository.existsByUserEmailAndNameAndParentId(USER_EMAIL, "Projects", 2L)).thenReturn(false);
        when(labelRepository.save(any(MailLabel.class))).thenAnswer(invocation -> {
            MailLabel lbl = invocation.getArgument(0);
            lbl.setId(3L);
            return lbl;
        });

        MailLabel child = labelService.createLabel(USER_EMAIL, "Projects", "#FBBC05", 2L);
        assertNotNull(child);
        assertEquals(3L, child.getId());
        assertEquals(2L, child.getParentId());
    }

    @Test
    @DisplayName("5. Prevent label from being nested inside itself")
    void testPreventLabelNestingInsideItself() {
        MailLabel label = MailLabel.builder().id(10L).userEmail(USER_EMAIL).name("Work").build();
        when(labelRepository.findById(10L)).thenReturn(Optional.of(label));

        MailException ex = assertThrows(MailException.class, () -> {
            labelService.updateLabel(USER_EMAIL, 10L, "Work", "#112233", 10L);
        });
        assertTrue(ex.getMessage().contains("cannot be its own parent"));
    }

    @Test
    @DisplayName("6. Prevent circular parent-child relationships (A -> B -> A)")
    void testPreventCircularRelationship() {
        // Label 1 (Parent A), Label 2 (Child B with parentId = 1)
        MailLabel labelA = MailLabel.builder().id(1L).userEmail(USER_EMAIL).name("Work").parentId(null).build();
        MailLabel labelB = MailLabel.builder().id(2L).userEmail(USER_EMAIL).name("Projects").parentId(1L).build();

        when(labelRepository.findById(1L)).thenReturn(Optional.of(labelA));
        when(labelRepository.findById(2L)).thenReturn(Optional.of(labelB));

        // Trying to update labelA to have parentId = 2 (which is already a child of 1)
        MailException ex = assertThrows(MailException.class, () -> {
            labelService.updateLabel(USER_EMAIL, 1L, "Work", "#112233", 2L);
        });
        assertTrue(ex.getMessage().contains("Circular parent-child relationship is not allowed"));
    }

    @Test
    @DisplayName("7. Cascading delete removes child labels and their email mappings")
    void testCascadeDeleteParentAndChildren() {
        MailLabel parent = MailLabel.builder().id(1L).userEmail(USER_EMAIL).name("Work").build();
        MailLabel child1 = MailLabel.builder().id(2L).userEmail(USER_EMAIL).name("Projects").parentId(1L).build();
        MailLabel subChild = MailLabel.builder().id(3L).userEmail(USER_EMAIL).name("2026").parentId(2L).build();

        when(labelRepository.findById(1L)).thenReturn(Optional.of(parent));
        when(labelRepository.findByParentId(1L)).thenReturn(List.of(child1));
        when(labelRepository.findByParentId(2L)).thenReturn(List.of(subChild));
        when(labelRepository.findByParentId(3L)).thenReturn(Collections.emptyList());

        labelService.deleteLabel(USER_EMAIL, 1L);

        // Child mappings deleted
        verify(mappingRepository, times(1)).deleteByLabelId(3L);
        verify(labelRepository, times(1)).delete(subChild);

        verify(mappingRepository, times(1)).deleteByLabelId(2L);
        verify(labelRepository, times(1)).delete(child1);

        verify(mappingRepository, times(1)).deleteByLabelId(1L);
        verify(labelRepository, times(1)).delete(parent);
    }

    @Test
    @DisplayName("8. Email assignment to nested child label and removal")
    void testApplyAndRemoveChildLabelOnEmail() {
        MailLabel child = MailLabel.builder().id(2L).userEmail(USER_EMAIL).name("Projects").parentId(1L).build();
        when(labelRepository.findById(2L)).thenReturn(Optional.of(child));
        when(mappingRepository.findByUserEmailAndEmailUidAndLabelId(USER_EMAIL, "msg-123", 2L))
                .thenReturn(Collections.emptyList())
                .thenReturn(List.of(MailLabelMapping.builder().id(100L).userEmail(USER_EMAIL).emailUid("msg-123").label(child).folderName("INBOX").build()));

        // Apply
        labelService.applyLabelToEmail(USER_EMAIL, "msg-123", "INBOX", 2L);
        verify(mappingRepository, times(1)).save(any(MailLabelMapping.class));

        // Remove
        labelService.removeLabelFromEmail(USER_EMAIL, "msg-123", "INBOX", 2L);
        verify(mappingRepository, times(1)).deleteAll(anyList());
    }
}
