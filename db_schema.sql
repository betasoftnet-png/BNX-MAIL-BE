-- Create mail application database
CREATE DATABASE IF NOT EXISTS mail_app_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE mail_app_db;

-- ==========================================
-- TABLE 0: ORGANIZATIONS (For Business Accounts)
-- ==========================================
CREATE TABLE IF NOT EXISTS organizations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    domain VARCHAR(255) UNIQUE NOT NULL,
    verification_token VARCHAR(100) NULL,
    verified BOOLEAN DEFAULT FALSE,
    created_by BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_domain (domain)
) ENGINE=InnoDB;

-- ==========================================
-- TABLE 1: USERS (Application users with JWT auth)
-- ==========================================
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    email VARCHAR(255) UNIQUE NULL,
    recovery_email VARCHAR(255) NULL,
    phone_number VARCHAR(50) NULL,
    password VARCHAR(255) NOT NULL,
    first_name VARCHAR(50),
    last_name VARCHAR(50),
    role VARCHAR(20) DEFAULT 'USER',
    account_type VARCHAR(20) DEFAULT 'PUBLIC',
    organization_id BIGINT NULL,
    parent_user_id BIGINT NULL,
    dob DATE NULL,
    active BOOLEAN DEFAULT TRUE,
    approved BOOLEAN DEFAULT TRUE,
    approved_by BIGINT NULL,
    approved_at TIMESTAMP NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    last_login TIMESTAMP NULL,
    profile_picture VARCHAR(255) NULL,
    FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE SET NULL,
    FOREIGN KEY (parent_user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_username (username),
    INDEX idx_email (email),
    INDEX idx_org (organization_id),
    INDEX idx_parent (parent_user_id)
) ENGINE=InnoDB;

-- ==========================================
-- TABLE: ORGANIZATION_INVITES
-- ==========================================
CREATE TABLE IF NOT EXISTS organization_invites (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    organization_id BIGINT NOT NULL,
    email VARCHAR(255) NOT NULL,
    invite_token VARCHAR(255) UNIQUE NOT NULL,
    role VARCHAR(50) DEFAULT 'ORG_USER',
    expires_at TIMESTAMP NOT NULL,
    accepted BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE,
    INDEX idx_token (invite_token)
) ENGINE=InnoDB;

-- ==========================================
-- TABLE: PASSWORD_RESET_TOKENS
-- ==========================================
CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token VARCHAR(255) NOT NULL,
    expiry_date DATETIME NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_token (token),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB;

-- ==========================================
-- TABLE 2: MAIL_ACCOUNTS (Link users to VPS mail accounts)
-- ==========================================
CREATE TABLE IF NOT EXISTS mail_accounts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    email_address VARCHAR(255) NOT NULL UNIQUE,
    display_name VARCHAR(100),
    vps_username VARCHAR(50) NOT NULL,
    vps_password VARCHAR(255) NOT NULL,
    smtp_host VARCHAR(100) DEFAULT 'mail.btctech.shop',
    smtp_port INT DEFAULT 587,
    imap_host VARCHAR(100) DEFAULT 'mail.btctech.shop',
    imap_port INT DEFAULT 993,
    is_default BOOLEAN DEFAULT FALSE,
    active BOOLEAN DEFAULT TRUE,
    storage_limit BIGINT DEFAULT 1073741824,
    storage_used BIGINT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    profile_picture VARCHAR(255) NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_user_id (user_id),
    INDEX idx_email (email_address)
) ENGINE=InnoDB;

-- ==========================================
-- TABLE 3: FOLDERS (Mail folders)
-- ==========================================
CREATE TABLE IF NOT EXISTS folders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    mail_account_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    folder_type VARCHAR(20) DEFAULT 'CUSTOM',
    parent_id BIGINT NULL,
    unread_count INT DEFAULT 0,
    total_count INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (mail_account_id) REFERENCES mail_accounts(id) ON DELETE CASCADE,
    FOREIGN KEY (parent_id) REFERENCES folders(id) ON DELETE CASCADE,
    INDEX idx_account (mail_account_id)
) ENGINE=InnoDB;

-- ==========================================
-- TABLE: MAIL_DRAFTS (Unsent email compositions)
-- ==========================================
CREATE TABLE IF NOT EXISTS mail_drafts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    mail_account_id BIGINT NOT NULL,
    to_address TEXT,
    cc_address TEXT,
    bcc_address TEXT,
    subject VARCHAR(255),
    body LONGTEXT,
    is_html BOOLEAN DEFAULT FALSE,
    last_opened_at TIMESTAMP NULL,
    attachments_json LONGTEXT NULL,
    status VARCHAR(20) DEFAULT 'DRAFT',
    failure_reason TEXT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (mail_account_id) REFERENCES mail_accounts(id) ON DELETE CASCADE,
    INDEX idx_draft_account (mail_account_id)
) ENGINE=InnoDB;

-- ==========================================
-- TABLE 4: DRAFT COLLABORATORS (Team Shared)
-- ==========================================
CREATE TABLE IF NOT EXISTS mail_draft_collaborators (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    draft_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    permission VARCHAR(20) NOT NULL, -- VIEW, EDIT, SEND
    FOREIGN KEY (draft_id) REFERENCES mail_drafts(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uk_draft_user (draft_id, user_id)
) ENGINE=InnoDB;

-- ==========================================
-- TABLE 4: MAILS (Email metadata for fast access)
-- ==========================================
CREATE TABLE IF NOT EXISTS mails (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    mail_account_id BIGINT NOT NULL,
    folder_id BIGINT NOT NULL,
    message_id VARCHAR(255),
    uid VARCHAR(100),
    from_address VARCHAR(255),
    to_address TEXT,
    cc_address TEXT,
    bcc_address TEXT,
    subject VARCHAR(500),
    body_text LONGTEXT,
    body_html LONGTEXT,
    sent_date DATETIME,
    received_date DATETIME,
    is_read BOOLEAN DEFAULT FALSE,
    is_starred BOOLEAN DEFAULT FALSE,
    is_deleted BOOLEAN DEFAULT FALSE,
    has_attachments BOOLEAN DEFAULT FALSE,
    size_bytes BIGINT DEFAULT 0,
    in_reply_to VARCHAR(255),
    `references` TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (mail_account_id) REFERENCES mail_accounts(id) ON DELETE CASCADE,
    FOREIGN KEY (folder_id) REFERENCES folders(id) ON DELETE CASCADE,
    INDEX idx_message_id (message_id),
    INDEX idx_folder (folder_id),
    INDEX idx_sent_date (sent_date),
    INDEX idx_is_read (is_read),
    INDEX idx_is_deleted (is_deleted)
) ENGINE=InnoDB;

-- ==========================================
-- TABLE 5: ATTACHMENTS
-- ==========================================
CREATE TABLE IF NOT EXISTS attachments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    mail_id BIGINT NOT NULL,
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100),
    size_bytes BIGINT,
    file_path VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (mail_id) REFERENCES mails(id) ON DELETE CASCADE,
    INDEX idx_mail_id (mail_id)
) ENGINE=InnoDB;

-- ==========================================
-- TABLE 6: CONTACTS
-- ==========================================
CREATE TABLE IF NOT EXISTS contacts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    email VARCHAR(255) NOT NULL,
    name VARCHAR(100),
    phone VARCHAR(50),
    company VARCHAR(100),
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_user_id (user_id),
    INDEX idx_email (email)
) ENGINE=InnoDB;

-- ==========================================
-- TABLE 7: LABELS/TAGS
-- ==========================================
CREATE TABLE IF NOT EXISTS labels (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(50) NOT NULL,
    color VARCHAR(7) DEFAULT '#000000',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY unique_user_label (user_id, name)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS mail_labels (
    mail_id BIGINT NOT NULL,
    label_id BIGINT NOT NULL,
    PRIMARY KEY (mail_id, label_id),
    FOREIGN KEY (mail_id) REFERENCES mails(id) ON DELETE CASCADE,
    FOREIGN KEY (label_id) REFERENCES labels(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ==========================================
-- CHAT SYSTEM TABLES
-- ==========================================
CREATE TABLE IF NOT EXISTS chats (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NULL,
    type VARCHAR(20) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS chat_members (
    chat_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (chat_id, user_id),
    FOREIGN KEY (chat_id) REFERENCES chats(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    chat_id BIGINT NOT NULL,
    sender_email VARCHAR(255) NOT NULL,
    content LONGTEXT NOT NULL,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (chat_id) REFERENCES chats(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ==========================================
-- TABLE: VERIFICATION_SESSIONS (For DigiLocker)
-- ==========================================
CREATE TABLE IF NOT EXISTS verification_sessions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    mail_account_id BIGINT NOT NULL,
    reference_id VARCHAR(100) UNIQUE NOT NULL,
    verification_id VARCHAR(100) NULL,
    status VARCHAR(20) DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (mail_account_id) REFERENCES mail_accounts(id) ON DELETE CASCADE,
    INDEX idx_reference (reference_id),
    INDEX idx_verification (verification_id)
) ENGINE=InnoDB;

-- ==========================================
-- TABLE: AUTHENTICATOR_ACCOUNTS (For Synced 2FA)
-- ==========================================
CREATE TABLE IF NOT EXISTS authenticator_accounts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    account_name VARCHAR(255) NOT NULL,
    secret_key VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB;

-- Add 2FA columns to users if not exists
ALTER TABLE users ADD COLUMN IF NOT EXISTS two_factor_enabled BOOLEAN DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS two_factor_secret VARCHAR(255) NULL;

-- ==========================================
-- TABLE: CASBOX_MESSAGES (Internal chat & messaging)
-- ==========================================
CREATE TABLE IF NOT EXISTS casbox_messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sender_email VARCHAR(255) NOT NULL,
    receiver_email VARCHAR(255) NOT NULL,
    subject VARCHAR(255) NULL,
    body LONGTEXT NULL,
    attachments_json LONGTEXT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'SENT',
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    sender_archived BOOLEAN NOT NULL DEFAULT FALSE,
    receiver_archived BOOLEAN NOT NULL DEFAULT FALSE,
    INDEX idx_casbox_sender (sender_email),
    INDEX idx_casbox_receiver (receiver_email),
    INDEX idx_casbox_timestamp (timestamp)
) ENGINE=InnoDB;

-- ==========================================
-- SAMPLE DATA
-- ==========================================
INSERT IGNORE INTO users (username, email, password, first_name, last_name, role) 
VALUES 
('admin', 'admin@btctech.shop', '$2a$10$dummyHashedPassword', 'Admin', 'User', 'ADMIN'),
('siva', 'siva@btctech.shop', '$2a$10$dummyHashedPassword', 'Siva', 'Kumar', 'USER');
