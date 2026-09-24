package com.btctech.mailapp.repository;

import com.btctech.mailapp.entity.ContactAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContactAliasRepository extends JpaRepository<ContactAlias, Long> {

    Optional<ContactAlias> findByOwnerUserIdAndContactUserId(Long ownerUserId, Long contactUserId);

    List<ContactAlias> findByOwnerUserId(Long ownerUserId);

    void deleteByOwnerUserIdAndContactUserId(Long ownerUserId, Long contactUserId);
}
