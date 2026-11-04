package com.btctech.mailapp.repository;

import com.btctech.mailapp.entity.MailAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;

public interface MailAccountRepository extends JpaRepository<MailAccount, Long> {
    Optional<MailAccount> findByEmail(String email);

    List<MailAccount> findByUserId(Long userId);

    Optional<MailAccount> findByUserIdAndIsPrimary(Long userId, Boolean isPrimary);

    boolean existsByEmail(String email);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE MailAccount m SET m.storageUsed = m.storageUsed + :bytesToAdd WHERE m.id = :accountId")
    void updateStorageUsed(Long accountId, long bytesToAdd);

    @org.springframework.data.jpa.repository.Query("SELECT SUM(m.storageUsed) FROM MailAccount m")
    Long calculateTotalStorageUsed();

    List<MailAccount> findByEmailEndingWith(String domain);

    @org.springframework.data.jpa.repository.Query("SELECT m FROM MailAccount m WHERE m.active = true AND (LOWER(m.email) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(m.emailName) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<MailAccount> searchMailAccounts(@org.springframework.data.repository.query.Param("query") String query);
}
