package com.btctech.mailapp.repository;

import com.btctech.mailapp.entity.SystemLabelSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SystemLabelSettingRepository extends JpaRepository<SystemLabelSetting, Long> {
    List<SystemLabelSetting> findByUserId(Long userId);
    Optional<SystemLabelSetting> findByUserIdAndLabelName(Long userId, String labelName);
}

