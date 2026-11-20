package com.btctech.mailapp.repository;

import com.btctech.mailapp.entity.ImportantEmail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ImportantEmailRepository extends JpaRepository<ImportantEmail, Long> {

    List<ImportantEmail> findByUserEmailAndIsImportantTrue(String userEmail);

    List<ImportantEmail> findByUserEmail(String userEmail);

    Optional<ImportantEmail> findByUserEmailAndUidAndFolderName(String userEmail, String uid, String folderName);

    List<ImportantEmail> findByUserEmailAndUid(String userEmail, String uid);

    Optional<ImportantEmail> findFirstByUserEmailAndUid(String userEmail, String uid);

    boolean existsByUserEmailAndUidAndIsImportantTrue(String userEmail, String uid);
}
