package com.btctech.mailapp.repository;

import com.btctech.mailapp.entity.MailLabel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface MailLabelRepository extends JpaRepository<MailLabel, Long> {
    List<MailLabel> findByUserEmail(String userEmail);
    List<MailLabel> findByParentId(Long parentId);
    boolean existsByUserEmailAndName(String userEmail, String name);
    boolean existsByUserEmailAndNameAndParentId(String userEmail, String name, Long parentId);
    boolean existsByUserEmailAndNameAndParentIdIsNull(String userEmail, String name);
}
