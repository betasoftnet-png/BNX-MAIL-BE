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

            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS processed_emails (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_email VARCHAR(150) NOT NULL,
                    message_identifier VARCHAR(255) NOT NULL,
                    sender_email VARCHAR(150),
                    subject VARCHAR(255),
                    folder VARCHAR(50),
                    processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_user_message_identifier (user_email, message_identifier),
                    INDEX idx_user_processed (user_email)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS chat_admins (
                    chat_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    PRIMARY KEY (chat_id, user_id),
                    INDEX idx_chat_admins_chat (chat_id),
                    INDEX idx_chat_admins_user (user_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

            try {
                jdbcTemplate.execute("""
                    INSERT IGNORE INTO chat_admins (chat_id, user_id)
                    SELECT id, creator_id FROM chats WHERE creator_id IS NOT NULL
                """);
            } catch (Exception ignoreEx) {
                // Ignore if chats table or creator_id is not yet initialized
            }

            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS system_label_settings (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    label_name VARCHAR(100) NOT NULL,
                    show_in_label_list BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_system_label_user_name (user_id, label_name),
                    INDEX idx_sys_label_user (user_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS user_recovery_codes (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    code_hash VARCHAR(128) NOT NULL,
                    used BOOLEAN NOT NULL DEFAULT FALSE,
                    used_at TIMESTAMP NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_rec_user (user_id),
                    INDEX idx_rec_user_used (user_id, used)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS pending_emails (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    send_id VARCHAR(64) NOT NULL UNIQUE,
                    user_email VARCHAR(150) NOT NULL,
                    user_id BIGINT,
                    mail_account_id BIGINT,
                    encrypted_password TEXT,
                    to_recipient TEXT NOT NULL,
                    cc_recipient TEXT,
                    bcc_recipient TEXT,
                    subject VARCHAR(500),
                    body LONGTEXT,
                    from_name VARCHAR(150),
                    is_html BOOLEAN DEFAULT TRUE,
                    attachments_json LONGTEXT,
                    draft_uid VARCHAR(100),
                    draft_id BIGINT,
                    is_starred BOOLEAN DEFAULT FALSE,
                    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_UNDO',
                    undo_deadline TIMESTAMP NOT NULL,
                    sent_at TIMESTAMP NULL,
                    sent_uid VARCHAR(100),
                    error_message TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    version BIGINT DEFAULT 0,
                    INDEX idx_pending_user_status (user_email, status),
                    INDEX idx_pending_status_deadline (status, undo_deadline)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

            try {
                jdbcTemplate.execute("ALTER TABLE pending_emails ADD COLUMN IF NOT EXISTS undo_deadline TIMESTAMP NULL");
            } catch (Exception ignoreEx) {
                // Column may already exist
            }

            try {
                jdbcTemplate.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS last_totp_step BIGINT NULL");
            } catch (Exception ignoreEx) {
                // Ignore if column already exists or syntax differences
            }

            try {
                jdbcTemplate.execute("ALTER TABLE users MODIFY COLUMN two_factor_secret VARCHAR(500) NULL");
            } catch (Exception ignoreEx) {
                // Ignore if syntax differences
            }

            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS important_emails (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_email VARCHAR(150) NOT NULL,
                    uid VARCHAR(100) NOT NULL,
                    folder_name VARCHAR(100) NOT NULL,
                    is_important BOOLEAN NOT NULL DEFAULT TRUE,
                    is_manual BOOLEAN NOT NULL DEFAULT FALSE,
                    score DOUBLE DEFAULT 0.0,
                    reasons TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_user_uid_folder (user_email, uid, folder_name),
                    INDEX idx_imp_user (user_email),
                    INDEX idx_imp_user_imp (user_email, is_important)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS user_sender_activity (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_email VARCHAR(150) NOT NULL,
                    sender_email VARCHAR(150) NOT NULL,
                    open_count INT NOT NULL DEFAULT 0,
                    reply_count INT NOT NULL DEFAULT 0,
                    sent_count INT NOT NULL DEFAULT 0,
                    thread_count INT NOT NULL DEFAULT 0,
                    last_opened_at TIMESTAMP NULL,
                    last_replied_at TIMESTAMP NULL,
                    last_interaction_at TIMESTAMP NULL,
                    reputation_score DOUBLE DEFAULT 0.0,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_user_sender (user_email, sender_email),
                    INDEX idx_act_user (user_email),
                    INDEX idx_act_user_sender (user_email, sender_email)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

            log.info("Database tables contact_aliases, connections, processed_emails, chat_admins, system_label_settings, user_recovery_codes, important_emails, and user_sender_activity are verified and ready.");
        } catch (Exception e) {
            log.error("Could not automatically create/verify tables: {}", e.getMessage());
        }
    }
}
