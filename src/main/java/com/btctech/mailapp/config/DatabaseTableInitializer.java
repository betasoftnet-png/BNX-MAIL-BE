package com.btctech.mailapp.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class DatabaseTableInitializer implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        ensureTablesExist();
    }

    public synchronized void ensureTablesExist() {
        try {
            log.info("Ensuring required database tables exist (contact_aliases, connections)...");

            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS contact_aliases (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    owner_user_id BIGINT NOT NULL,
                    contact_user_id BIGINT NOT NULL,
                    custom_name VARCHAR(150) NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_owner_contact_user (owner_user_id, contact_user_id),
                    INDEX idx_alias_owner (owner_user_id),
                    INDEX idx_alias_contact (contact_user_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS connections (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    requester_id BIGINT NOT NULL,
                    receiver_id BIGINT NOT NULL,
                    status VARCHAR(30) NOT NULL DEFAULT 'CONNECTED',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_requester_receiver (requester_id, receiver_id),
                    INDEX idx_conn_requester (requester_id),
                    INDEX idx_conn_receiver (receiver_id),
                    INDEX idx_conn_status (status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

            log.info("Database tables contact_aliases and connections are verified and ready.");
        } catch (Exception e) {
            log.error("Could not automatically create/verify tables: {}", e.getMessage());
        }
    }
}
