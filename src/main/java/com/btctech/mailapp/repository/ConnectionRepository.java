package com.btctech.mailapp.repository;

import com.btctech.mailapp.entity.Connection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConnectionRepository extends JpaRepository<Connection, Long> {

    @Query("SELECT c FROM Connection c WHERE (c.requesterId = :u1 AND c.receiverId = :u2) OR (c.requesterId = :u2 AND c.receiverId = :u1)")
    Optional<Connection> findConnectionBetweenUsers(@Param("u1") Long u1, @Param("u2") Long u2);

    @Query("SELECT c FROM Connection c WHERE c.requesterId = :userId OR c.receiverId = :userId")
    List<Connection> findAllForUser(@Param("userId") Long userId);

    @Query("SELECT c FROM Connection c WHERE (c.requesterId = :userId OR c.receiverId = :userId) AND c.status IN ('ACCEPTED', 'CONNECTED', 'DISCONNECTED')")
    List<Connection> findAcceptedConnectionsForUser(@Param("userId") Long userId);
}
