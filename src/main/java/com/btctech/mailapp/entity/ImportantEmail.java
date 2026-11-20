package com.btctech.mailapp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "important_emails", uniqueConstraints = {
    @UniqueConstraint(name = "uk_user_uid_folder", columnNames = {"user_email", "uid", "folder_name"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportantEmail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_email", nullable = false)
    private String userEmail;

    @Column(nullable = false)
    private String uid;

    @Column(name = "folder_name", nullable = false)
    private String folderName;

    @Column(name = "is_important", nullable = false)
    @Builder.Default
    private boolean isImportant = true;

    @Column(name = "is_manual", nullable = false)
    @Builder.Default
    private boolean isManual = false;

    @Column
    private Double score;

    @Column(columnDefinition = "TEXT")
    private String reasons;

    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();
}
