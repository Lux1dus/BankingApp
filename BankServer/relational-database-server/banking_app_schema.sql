/* =====================================================================
   BANKINGAPP — UNIFIED ERD (14 TABLES) - SQL Server Schema & Seed Data
   Đồng bộ 100% với ERD_new.html & Class_Unified.html:
   - Member 1 (Core Banking - 5 bảng):
     1. users
     2. accounts
     3. saving_rates
     4. saving_details
     5. mortgage_details
   - Member 2 (Security & Transactions - 4 bảng):
     6. ekyc_verifications
     7. security_audit_logs
     8. transactions
     9. otp_challenges
   - Member 3 (Utilities, Navigation & Chatbot - 5 bảng):
     10. utility_services
     11. utility_orders
     12. bank_branches
     13. chatbot_sessions
     14. chat_messages
   ===================================================================== */
IF DB_ID(N'BankingApp') IS NULL
    CREATE DATABASE BankingApp;
GO
USE BankingApp;
GO

/* ---------- Xóa View/Bảng cũ nếu tồn tại (theo thứ tự phụ thuộc FK) ---------- */
DROP VIEW IF EXISTS dbo.CHATBOT_SESSIONS;
GO
DROP TABLE IF EXISTS dbo.chat_messages;
DROP TABLE IF EXISTS dbo.chatbot_sessions;
DROP TABLE IF EXISTS dbo.CHAT_SESSIONS;
DROP TABLE IF EXISTS dbo.utility_orders;
DROP TABLE IF EXISTS dbo.utility_services;
DROP TABLE IF EXISTS dbo.SERVICE_BOOKINGS;
DROP TABLE IF EXISTS dbo.BILLS;
DROP TABLE IF EXISTS dbo.otp_challenges;
DROP TABLE IF EXISTS dbo.OTP_LOGS;
DROP TABLE IF EXISTS dbo.security_audit_logs;
DROP TABLE IF EXISTS dbo.ekyc_verifications;
DROP TABLE IF EXISTS dbo.EKYC_PROFILES;
DROP TABLE IF EXISTS dbo.transactions;
DROP TABLE IF EXISTS dbo.saving_details;
DROP TABLE IF EXISTS dbo.mortgage_details;
DROP TABLE IF EXISTS dbo.saving_rates;
DROP TABLE IF EXISTS dbo.accounts;
DROP TABLE IF EXISTS dbo.bank_branches;
DROP TABLE IF EXISTS dbo.users;
GO

/* =====================================================================
   PHÂN HỆ MEMBER 1: CORE BANKING (5 BẢNG)
   ===================================================================== */

/* ---------- 1. [M1 -> SHARED] users ---------- */
CREATE TABLE dbo.users (
    user_id        NVARCHAR(36)  NOT NULL CONSTRAINT PK_users PRIMARY KEY
                                 CONSTRAINT DF_users_id DEFAULT (CONVERT(NVARCHAR(36), NEWID())),
    username       NVARCHAR(50)  NOT NULL CONSTRAINT UQ_users_username UNIQUE,
    password_hash  NVARCHAR(255) NOT NULL,
    full_name      NVARCHAR(150) NOT NULL,
    email          NVARCHAR(150) NULL,
    phone_number   NVARCHAR(20)  NULL,
    role           NVARCHAR(20)  NOT NULL CONSTRAINT DF_users_role DEFAULT ('CUSTOMER'),
    status         NVARCHAR(20)  NOT NULL CONSTRAINT DF_users_status DEFAULT ('ACTIVE'),
    created_at     DATETIME2(0)  NOT NULL CONSTRAINT DF_users_created DEFAULT (SYSUTCDATETIME()),
    CONSTRAINT CK_users_role   CHECK (role   IN ('CUSTOMER','OFFICER')),
    CONSTRAINT CK_users_status CHECK (status IN ('ACTIVE','LOCKED','PENDING_EKYC','SUSPENDED'))
);
CREATE UNIQUE INDEX UX_users_email ON dbo.users(email)        WHERE email IS NOT NULL;
CREATE UNIQUE INDEX UX_users_phone ON dbo.users(phone_number) WHERE phone_number IS NOT NULL;
GO

/* ---------- 2. [M1 -> SHARED] accounts ---------- */
CREATE TABLE dbo.accounts (
    account_id     NVARCHAR(36)  NOT NULL CONSTRAINT PK_accounts PRIMARY KEY
                                 CONSTRAINT DF_accounts_id DEFAULT (CONVERT(NVARCHAR(36), NEWID())),
    account_number NVARCHAR(20)  NOT NULL CONSTRAINT UQ_accounts_number UNIQUE,
    user_id        NVARCHAR(36)  NOT NULL,
    account_type   NVARCHAR(20)  NOT NULL,
    balance        DECIMAL(18,2) NOT NULL CONSTRAINT DF_accounts_balance DEFAULT (0),
    currency       NVARCHAR(10)  NOT NULL CONSTRAINT DF_accounts_currency DEFAULT ('VND'),
    status         NVARCHAR(20)  NOT NULL CONSTRAINT DF_accounts_status DEFAULT ('ACTIVE'),
    created_at     DATETIME2(0)  NOT NULL CONSTRAINT DF_accounts_created DEFAULT (SYSUTCDATETIME()),
    CONSTRAINT FK_accounts_user   FOREIGN KEY (user_id) REFERENCES dbo.users(user_id),
    CONSTRAINT CK_accounts_type   CHECK (account_type IN ('CHECKING','SAVING','MORTGAGE')),
    CONSTRAINT CK_accounts_status CHECK (status IN ('ACTIVE','FROZEN','CLOSED'))
);
CREATE INDEX IX_accounts_user ON dbo.accounts(user_id);
GO

/* ---------- 3. [M1 - Internal] saving_rates ---------- */
CREATE TABLE dbo.saving_rates (
    rate_id               NVARCHAR(36)  NOT NULL CONSTRAINT PK_saving_rates PRIMARY KEY
                                        CONSTRAINT DF_sr_id DEFAULT (CONVERT(NVARCHAR(36), NEWID())),
    term_months           INT           NOT NULL,
    interest_rate         DECIMAL(9,4)  NOT NULL,   -- % / năm
    updated_by_officer_id NVARCHAR(36)  NULL,
    updated_at            DATETIME2(0)  NOT NULL CONSTRAINT DF_sr_updated DEFAULT (SYSUTCDATETIME()),
    CONSTRAINT FK_sr_officer FOREIGN KEY (updated_by_officer_id) REFERENCES dbo.users(user_id),
    CONSTRAINT CK_sr_term    CHECK (term_months > 0),
    CONSTRAINT CK_sr_rate    CHECK (interest_rate >= 0)
);
GO

/* ---------- 4. [M1 - Internal] saving_details ---------- */
CREATE TABLE dbo.saving_details (
    account_id      NVARCHAR(36)  NOT NULL CONSTRAINT PK_saving_details PRIMARY KEY,
    rate_id         NVARCHAR(36)  NOT NULL,
    initial_deposit DECIMAL(18,2) NOT NULL,
    start_date      DATETIME2(0)  NOT NULL,
    maturity_date   DATETIME2(0)  NOT NULL,
    monthly_profit  DECIMAL(18,2) NOT NULL CONSTRAINT DF_sd_profit DEFAULT (0),
    auto_renew      BIT           NOT NULL CONSTRAINT DF_sd_renew DEFAULT (0),
    CONSTRAINT FK_sd_account FOREIGN KEY (account_id) REFERENCES dbo.accounts(account_id),
    CONSTRAINT FK_sd_rate    FOREIGN KEY (rate_id)    REFERENCES dbo.saving_rates(rate_id),
    CONSTRAINT CK_sd_dates   CHECK (maturity_date > start_date)
);
CREATE INDEX IX_sd_rate ON dbo.saving_details(rate_id);
GO

/* ---------- 5. [M1 - Internal] mortgage_details ---------- */
CREATE TABLE dbo.mortgage_details (
    account_id        NVARCHAR(36)  NOT NULL CONSTRAINT PK_mortgage_details PRIMARY KEY,
    total_loan        DECIMAL(18,2) NOT NULL,
    remaining_debt    DECIMAL(18,2) NOT NULL,
    interest_rate     DECIMAL(9,4)  NOT NULL,
    payment_frequency NVARCHAR(20)  NOT NULL,
    payment_amount    DECIMAL(18,2) NOT NULL,
    next_due_date     DATETIME2(0)  NULL,
    CONSTRAINT FK_md_account FOREIGN KEY (account_id) REFERENCES dbo.accounts(account_id),
    CONSTRAINT CK_md_freq    CHECK (payment_frequency IN ('BI_WEEKLY','MONTHLY')),
    CONSTRAINT CK_md_debt    CHECK (remaining_debt >= 0 AND remaining_debt <= total_loan)
);
GO

/* =====================================================================
   PHÂN HỆ MEMBER 2: SECURITY & TRANSACTIONS (4 BẢNG)
   ===================================================================== */

/* ---------- 6. [M2 - Internal] ekyc_verifications ---------- */
CREATE TABLE dbo.ekyc_verifications (
    ekyc_id            NVARCHAR(36)  NOT NULL CONSTRAINT PK_ekyc_verifications PRIMARY KEY
                                     CONSTRAINT DF_ekyc_id DEFAULT (CONVERT(NVARCHAR(36), NEWID())),
    user_id            NVARCHAR(36)  NOT NULL,
    id_card_number     NVARCHAR(20)  NOT NULL,
    document_photo_url NVARCHAR(500) NOT NULL,
    face_photo_url     NVARCHAR(500) NOT NULL,
    status             NVARCHAR(20)  NOT NULL CONSTRAINT DF_ekyc_status DEFAULT ('PENDING'),
    reviewed_by        NVARCHAR(36)  NULL,
    reviewed_at        DATETIME2(0)  NULL,
    rejection_reason   NVARCHAR(500) NULL,
    created_at         DATETIME2(0)  NOT NULL CONSTRAINT DF_ekyc_created DEFAULT (SYSUTCDATETIME()),
    updated_at         DATETIME2(0)  NOT NULL CONSTRAINT DF_ekyc_updated DEFAULT (SYSUTCDATETIME()),
    CONSTRAINT FK_ekyc_user     FOREIGN KEY (user_id)     REFERENCES dbo.users(user_id),
    CONSTRAINT FK_ekyc_reviewer FOREIGN KEY (reviewed_by) REFERENCES dbo.users(user_id),
    CONSTRAINT CK_ekyc_status   CHECK (status IN ('PENDING','APPROVED','REJECTED'))
);
CREATE INDEX IX_ekyc_user ON dbo.ekyc_verifications(user_id, created_at DESC);
GO

/* ---------- 7. [M2 - Internal] security_audit_logs ---------- */
CREATE TABLE dbo.security_audit_logs (
    log_id      NVARCHAR(36)  NOT NULL CONSTRAINT PK_security_audit_logs PRIMARY KEY
                              CONSTRAINT DF_audit_id DEFAULT (CONVERT(NVARCHAR(36), NEWID())),
    actor_id    NVARCHAR(36)  NOT NULL,
    action      NVARCHAR(100) NOT NULL,
    entity_name NVARCHAR(100) NOT NULL,
    entity_id   NVARCHAR(100) NOT NULL,
    status      NVARCHAR(50)  NOT NULL,
    details     NVARCHAR(MAX) NULL,
    ip_address  NVARCHAR(50)  NULL,
    timestamp   DATETIME2(0)  NOT NULL CONSTRAINT DF_audit_ts DEFAULT (SYSUTCDATETIME()),
    CONSTRAINT FK_audit_actor FOREIGN KEY (actor_id) REFERENCES dbo.users(user_id)
);
CREATE INDEX IX_audit_actor ON dbo.security_audit_logs(actor_id, timestamp DESC);
GO

/* ---------- 8. [M2 -> SHARED] transactions ---------- */
CREATE TABLE dbo.transactions (
    transaction_id        NVARCHAR(36)  NOT NULL CONSTRAINT PK_transactions PRIMARY KEY
                                        CONSTRAINT DF_txn_id DEFAULT (CONVERT(NVARCHAR(36), NEWID())),
    user_id               NVARCHAR(36)  NOT NULL,
    transaction_type      NVARCHAR(30)  NOT NULL,
    source_account_id     NVARCHAR(36)  NULL,
    target_account_id     NVARCHAR(36)  NULL,
    target_account_number NVARCHAR(36)  NULL,
    target_bank_code      NVARCHAR(20)  NULL,
    receiver_name         NVARCHAR(150) NULL,
    amount                DECIMAL(18,2) NOT NULL,
    currency              NVARCHAR(10)  NOT NULL CONSTRAINT DF_txn_currency DEFAULT ('VND'),
    description           NVARCHAR(500) NULL,
    status                NVARCHAR(20)  NOT NULL CONSTRAINT DF_txn_status DEFAULT ('PENDING'),
    is_high_value         BIT           NOT NULL CONSTRAINT DF_txn_high_val DEFAULT (0),
    is_2fa_verified       BIT           NOT NULL CONSTRAINT DF_txn_2fa DEFAULT (0),
    is_biometric_verified BIT           NOT NULL CONSTRAINT DF_txn_bio DEFAULT (0),
    created_at            DATETIME2(0)  NOT NULL CONSTRAINT DF_txn_created DEFAULT (SYSUTCDATETIME()),
    completed_at          DATETIME2(0)  NULL,
    CONSTRAINT FK_txn_user   FOREIGN KEY (user_id)           REFERENCES dbo.users(user_id),
    CONSTRAINT FK_txn_source FOREIGN KEY (source_account_id) REFERENCES dbo.accounts(account_id),
    CONSTRAINT FK_txn_target FOREIGN KEY (target_account_id) REFERENCES dbo.accounts(account_id),
    CONSTRAINT CK_txn_amount CHECK (amount > 0),
    CONSTRAINT CK_txn_type   CHECK (transaction_type IN (
        'TRANSFER','DEPOSIT','WITHDRAW','BILL_PAYMENT','TOPUP','TICKET_BOOKING','ECOMMERCE','BILL','UTILITY'
    )),
    CONSTRAINT CK_txn_status CHECK (status IN (
        'PENDING','OTP_SENT','PROCESSING','COMPLETED','SUCCESS','FAILED','CANCELLED','REFUNDED'
    ))
);
CREATE INDEX IX_txn_user   ON dbo.transactions(user_id, created_at DESC);
CREATE INDEX IX_txn_source ON dbo.transactions(source_account_id, created_at DESC);
GO

/* ---------- 9. [M2 - Internal] otp_challenges ---------- */
CREATE TABLE dbo.otp_challenges (
    challenge_id   NVARCHAR(36)  NOT NULL CONSTRAINT PK_otp_challenges PRIMARY KEY
                                 CONSTRAINT DF_otp_id DEFAULT (CONVERT(NVARCHAR(36), NEWID())),
    transaction_id NVARCHAR(36)  NOT NULL,
    user_id        NVARCHAR(36)  NOT NULL,
    otp_hash       NVARCHAR(255) NOT NULL,
    channel        NVARCHAR(20)  NOT NULL CONSTRAINT DF_otp_channel DEFAULT ('SMS'),
    attempts_count INT           NOT NULL CONSTRAINT DF_otp_attempts DEFAULT (0),
    max_attempts   INT           NOT NULL CONSTRAINT DF_otp_max DEFAULT (3),
    is_used        BIT           NOT NULL CONSTRAINT DF_otp_used DEFAULT (0),
    expires_at     DATETIME2(0)  NOT NULL,
    status         NVARCHAR(20)  NOT NULL CONSTRAINT DF_otp_status DEFAULT ('PENDING'),
    created_at     DATETIME2(0)  NOT NULL CONSTRAINT DF_otp_created DEFAULT (SYSUTCDATETIME()),
    CONSTRAINT FK_otp_txn     FOREIGN KEY (transaction_id) REFERENCES dbo.transactions(transaction_id),
    CONSTRAINT FK_otp_user    FOREIGN KEY (user_id)        REFERENCES dbo.users(user_id),
    CONSTRAINT CK_otp_channel CHECK (channel IN ('SMS','EMAIL','SMART_OTP')),
    CONSTRAINT CK_otp_status  CHECK (status  IN ('PENDING','VERIFIED','USED','EXPIRED','FAILED'))
);
CREATE INDEX IX_otp_txn  ON dbo.otp_challenges(transaction_id, created_at DESC);
CREATE INDEX IX_otp_user ON dbo.otp_challenges(user_id, created_at DESC);
GO

/* =====================================================================
   PHÂN HỆ MEMBER 3: UTILITIES, NAVIGATION & CHATBOT (5 BẢNG)
   ===================================================================== */

/* ---------- 10. [M3 - Internal] utility_services ---------- */
CREATE TABLE dbo.utility_services (
    service_id         NVARCHAR(36)  NOT NULL CONSTRAINT PK_utility_services PRIMARY KEY
                                     CONSTRAINT DF_us_id DEFAULT (CONVERT(NVARCHAR(36), NEWID())),
    provider_code      NVARCHAR(50)  NOT NULL CONSTRAINT UQ_us_provider UNIQUE,
    service_name       NVARCHAR(150) NOT NULL,
    service_type       NVARCHAR(20)  NOT NULL,
    aggregator_gateway NVARCHAR(20)  NOT NULL CONSTRAINT DF_us_gw DEFAULT ('VNPAY'),
    status             NVARCHAR(20)  NOT NULL CONSTRAINT DF_us_status DEFAULT ('ACTIVE'),
    created_at         DATETIME2(0)  NOT NULL CONSTRAINT DF_us_created DEFAULT (SYSUTCDATETIME()),
    CONSTRAINT CK_us_type   CHECK (service_type IN ('ELECTRICITY','WATER','PHONE_TOPUP','FLIGHT','MOVIE','HOTEL','ECOMMERCE')),
    CONSTRAINT CK_us_gw     CHECK (aggregator_gateway IN ('VNPAY','PAYOO','NAPAS','DIRECT')),
    CONSTRAINT CK_us_status CHECK (status IN ('ACTIVE','MAINTENANCE','SUSPENDED'))
);
GO

/* ---------- 11. [M3 - Internal] utility_orders ---------- */
CREATE TABLE dbo.utility_orders (
    order_id             NVARCHAR(36)  NOT NULL CONSTRAINT PK_utility_orders PRIMARY KEY
                                       CONSTRAINT DF_uo_id DEFAULT (CONVERT(NVARCHAR(36), NEWID())),
    user_id              NVARCHAR(36)  NOT NULL,
    service_id           NVARCHAR(36)  NOT NULL,
    transaction_id       NVARCHAR(36)  NULL,
    provider_customer_id NVARCHAR(100) NOT NULL,
    total_amount         DECIMAL(18,2) NOT NULL,
    metadata_payload     NVARCHAR(MAX) NULL,
    status               NVARCHAR(20)  NOT NULL CONSTRAINT DF_uo_status DEFAULT ('PENDING'),
    hold_expires_at      DATETIME2(0)  NULL,
    created_at           DATETIME2(0)  NOT NULL CONSTRAINT DF_uo_created DEFAULT (SYSUTCDATETIME()),
    CONSTRAINT FK_uo_user    FOREIGN KEY (user_id)        REFERENCES dbo.users(user_id),
    CONSTRAINT FK_uo_service FOREIGN KEY (service_id)     REFERENCES dbo.utility_services(service_id),
    CONSTRAINT FK_uo_txn     FOREIGN KEY (transaction_id) REFERENCES dbo.transactions(transaction_id),
    CONSTRAINT CK_uo_status  CHECK (status IN ('PENDING','UNPAID','PAID','COMPLETED','FAILED','EXPIRED','REFUNDED')),
    CONSTRAINT CK_uo_json    CHECK (metadata_payload IS NULL OR ISJSON(metadata_payload) = 1)
);
CREATE INDEX IX_uo_user    ON dbo.utility_orders(user_id, created_at DESC);
CREATE INDEX IX_uo_service ON dbo.utility_orders(service_id);
CREATE INDEX IX_uo_txn     ON dbo.utility_orders(transaction_id) WHERE transaction_id IS NOT NULL;
GO

/* ---------- 12. [M3 - Internal] bank_branches ---------- */
CREATE TABLE dbo.bank_branches (
    branch_id     NVARCHAR(36)  NOT NULL CONSTRAINT PK_bank_branches PRIMARY KEY
                                CONSTRAINT DF_br_id DEFAULT (CONVERT(NVARCHAR(36), NEWID())),
    name          NVARCHAR(150) NOT NULL,
    branch_type   NVARCHAR(20)  NOT NULL CONSTRAINT DF_br_type DEFAULT ('BRANCH'),
    address       NVARCHAR(300) NOT NULL,
    latitude      FLOAT         NOT NULL,
    longitude     FLOAT         NOT NULL,
    opening_hours NVARCHAR(100) NULL,
    status        NVARCHAR(20)  NOT NULL CONSTRAINT DF_br_status DEFAULT ('OPEN'),
    CONSTRAINT CK_br_type   CHECK (branch_type IN ('BRANCH','ATM','BOTH')),
    CONSTRAINT CK_br_status CHECK (status      IN ('OPEN','CLOSED','MAINTENANCE')),
    CONSTRAINT CK_br_lat    CHECK (latitude  BETWEEN -90  AND 90),
    CONSTRAINT CK_br_lng    CHECK (longitude BETWEEN -180 AND 180)
);
GO

/* ---------- 13. [M3 - Internal] chatbot_sessions ---------- */
CREATE TABLE dbo.chatbot_sessions (
    session_id NVARCHAR(36) NOT NULL CONSTRAINT PK_chatbot_sessions PRIMARY KEY
                            CONSTRAINT DF_cs_id DEFAULT (CONVERT(NVARCHAR(36), NEWID())),
    user_id    NVARCHAR(36) NOT NULL,
    status     NVARCHAR(20) NOT NULL CONSTRAINT DF_cs_status DEFAULT ('ACTIVE'),
    started_at DATETIME2(0) NOT NULL CONSTRAINT DF_cs_started DEFAULT (SYSUTCDATETIME()),
    ended_at   DATETIME2(0) NULL,
    CONSTRAINT FK_cs_user   FOREIGN KEY (user_id) REFERENCES dbo.users(user_id),
    CONSTRAINT CK_cs_status CHECK (status IN ('ACTIVE','ENDED'))
);
CREATE INDEX IX_cs_user ON dbo.chatbot_sessions(user_id);
GO

/* ---------- 14. [M3 - Internal] chat_messages ---------- */
CREATE TABLE dbo.chat_messages (
    message_id              NVARCHAR(36)  NOT NULL CONSTRAINT PK_chat_messages PRIMARY KEY
                                          CONSTRAINT DF_cm_id DEFAULT (CONVERT(NVARCHAR(36), NEWID())),
    session_id              NVARCHAR(36)  NOT NULL,
    sender                  NVARCHAR(10)  NOT NULL,
    input_type              NVARCHAR(20)  NOT NULL CONSTRAINT DF_cm_input DEFAULT ('TEXT'),
    message_text            NVARCHAR(MAX) NOT NULL,
    detected_intent         NVARCHAR(50)  NULL,
    extracted_entities_json NVARCHAR(MAX) NULL,
    action_type             NVARCHAR(20)  NOT NULL CONSTRAINT DF_cm_act_type DEFAULT ('NONE'),
    action_destination      NVARCHAR(200) NULL,
    action_params_json      NVARCHAR(MAX) NULL,
    created_at              DATETIME2(0)  NOT NULL CONSTRAINT DF_cm_created DEFAULT (SYSUTCDATETIME()),
    CONSTRAINT FK_cm_session  FOREIGN KEY (session_id) REFERENCES dbo.chatbot_sessions(session_id),
    CONSTRAINT CK_cm_sender   CHECK (sender IN ('USER','BOT')),
    CONSTRAINT CK_cm_input    CHECK (input_type IN ('TEXT','VOICE','SUGGESTION_CHIP')),
    CONSTRAINT CK_cm_act_type CHECK (action_type IN ('NONE','NAVIGATE'))
);
CREATE INDEX IX_cm_session ON dbo.chat_messages(session_id, created_at);
GO

/* =====================================================================
   KHỞI TẠO DỮ LIỆU MẪU (SEED DATA) CHO ĐẦY ĐỦ 14 BẢNG TRONG ERD_new.html
   ===================================================================== */

-- 1. Seed users
INSERT INTO dbo.users (user_id, username, password_hash, full_name, email, phone_number, role, status) VALUES
('u1111111-1111-1111-1111-111111111111', 'nguyenvana', 'hashed_pw_123', N'Nguyễn Văn A', 'vana@example.com', '0987654321', 'CUSTOMER', 'ACTIVE'),
('u2222222-2222-2222-2222-222222222222', 'nguyenthib', 'hashed_pw_123', N'Nguyễn Thị B', 'me_b@example.com', '0901234567', 'CUSTOMER', 'ACTIVE'),
('u9999999-9999-9999-9999-999999999999', 'officer01',  'hashed_pw_123', N'Trần Quản Lý', 'officer@bank.vn',  '0912345678', 'OFFICER',  'ACTIVE');
GO

-- 2. Seed accounts (CHECKING, SAVING, MORTGAGE)
INSERT INTO dbo.accounts (account_id, account_number, user_id, account_type, balance, currency, status) VALUES
('a1b2c3d4-0000-0000-0000-000000000001', '190088889999', 'u1111111-1111-1111-1111-111111111111', 'CHECKING', 50000000,  'VND', 'ACTIVE'),
('a1b2c3d4-0000-0000-0000-000000000002', '190088887777', 'u1111111-1111-1111-1111-111111111111', 'SAVING',   100000000, 'VND', 'ACTIVE'),
('a1b2c3d4-0000-0000-0000-000000000003', '0123456789',   'u2222222-2222-2222-2222-222222222222', 'CHECKING', 15000000,  'VND', 'ACTIVE'),
('a1b2c3d4-0000-0000-0000-000000000004', '190088886666', 'u1111111-1111-1111-1111-111111111111', 'MORTGAGE', 500000000, 'VND', 'ACTIVE');
GO

-- 3. Seed saving_rates
INSERT INTO dbo.saving_rates (rate_id, term_months, interest_rate, updated_by_officer_id) VALUES
('rate-01m', 1,  3.2000, 'u9999999-9999-9999-9999-999999999999'),
('rate-03m', 3,  3.8000, 'u9999999-9999-9999-9999-999999999999'),
('rate-06m', 6,  5.1000, 'u9999999-9999-9999-9999-999999999999'),
('rate-12m', 12, 6.2000, 'u9999999-9999-9999-9999-999999999999'),
('rate-24m', 24, 6.5000, 'u9999999-9999-9999-9999-999999999999');
GO

-- 4. Seed saving_details
INSERT INTO dbo.saving_details (account_id, rate_id, initial_deposit, start_date, maturity_date, monthly_profit, auto_renew) VALUES
('a1b2c3d4-0000-0000-0000-000000000002', 'rate-06m', 100000000, '2026-06-01T00:00:00', '2026-12-01T00:00:00', 425000, 1);
GO

-- 5. Seed mortgage_details
INSERT INTO dbo.mortgage_details (account_id, total_loan, remaining_debt, interest_rate, payment_frequency, payment_amount, next_due_date) VALUES
('a1b2c3d4-0000-0000-0000-000000000004', 500000000, 420000000, 8.5000, 'MONTHLY', 12500000, '2026-12-05T00:00:00');
GO

-- 6. Seed ekyc_verifications
INSERT INTO dbo.ekyc_verifications (ekyc_id, user_id, id_card_number, document_photo_url, face_photo_url, status, reviewed_by, reviewed_at) VALUES
('ekyc-0001', 'u1111111-1111-1111-1111-111111111111', '079095001234', 'https://storage.bank.vn/ekyc/u1_cccd.jpg', 'https://storage.bank.vn/ekyc/u1_face.jpg', 'APPROVED', 'u9999999-9999-9999-9999-999999999999', SYSUTCDATETIME());
GO

-- 7. Seed security_audit_logs
INSERT INTO dbo.security_audit_logs (log_id, actor_id, action, entity_name, entity_id, status, details, ip_address) VALUES
('audit-0001', 'u9999999-9999-9999-9999-999999999999', 'APPROVE_EKYC', 'ekyc_verifications', 'ekyc-0001', 'SUCCESS', N'Phê duyệt hồ sơ định danh eKYC cho khách hàng Nguyễn Văn A', '10.0.0.1');
GO

-- 8. Seed utility_services (7 nhóm tiện ích & đối tác theo ERD_new.html)
INSERT INTO dbo.utility_services (service_id, provider_code, service_name, service_type, aggregator_gateway, status) VALUES
('srv-evn-hcm',     'EVN_HCM',     N'Điện lực TP.HCM (EVNHCMC)',         'ELECTRICITY', 'PAYOO',  'ACTIVE'),
('srv-sawaco',      'SAWACO',      N'Cấp nước Sài Gòn (SAWACO)',         'WATER',       'PAYOO',  'ACTIVE'),
('srv-viettel',     'VIETTEL',     N'Viễn thông Viettel Telecom',        'PHONE_TOPUP', 'NAPAS',  'ACTIVE'),
('srv-vinaphone',   'VINAPHONE',   N'Viễn thông VinaPhone (VNPT)',       'PHONE_TOPUP', 'NAPAS',  'ACTIVE'),
('srv-mobifone',    'MOBIFONE',    N'Viễn thông MobiFone',               'PHONE_TOPUP', 'NAPAS',  'ACTIVE'),
('srv-vna',         'VNA',         N'Hãng hàng không Vietnam Airlines',  'FLIGHT',      'VNPAY',  'ACTIVE'),
('srv-vietjet',     'VIETJET',     N'Hãng hàng không Vietjet Air',       'FLIGHT',      'VNPAY',  'ACTIVE'),
('srv-cgv',         'CGV',         N'Hệ thống rạp chiếu phim CGV',       'MOVIE',       'VNPAY',  'ACTIVE'),
('srv-muong-thanh', 'MUONG_THANH', N'Chuỗi khách sạn Mường Thanh',       'HOTEL',       'VNPAY',  'ACTIVE'),
('srv-shopee',      'SHOPEE',      N'Sàn TMĐT Shopee Việt Nam',          'ECOMMERCE',   'VNPAY',  'ACTIVE'),
('srv-lazada',      'LAZADA',      N'Sàn TMĐT Lazada',                   'ECOMMERCE',   'VNPAY',  'ACTIVE'),
('srv-amazon',      'AMAZON',      N'Sàn TMĐT Amazon Global',            'ECOMMERCE',   'DIRECT', 'ACTIVE');
GO

-- 9. Seed utility_orders (Hóa đơn Điện PE123456 & Nước SW987654 chưa thanh toán của Nguyễn Văn A theo Polymorphic Schema)
INSERT INTO dbo.utility_orders (order_id, user_id, service_id, transaction_id, provider_customer_id, total_amount, metadata_payload, status) VALUES
(
    'b9f1c2d3-0000-0000-0000-000000000001',
    'u1111111-1111-1111-1111-111111111111',
    'srv-evn-hcm',
    NULL,
    'PE123456',
    850000,
    N'{"bill_type":"ELECTRICITY","provider_code":"EVN_HCM","customer_name":"Nguyễn Văn A","customer_address":"123 Nguyễn Huệ, Quận 1, TP.HCM","billing_period":"11/2026"}',
    'PENDING'
),
(
    'b9f1c2d3-0000-0000-0000-000000000002',
    'u1111111-1111-1111-1111-111111111111',
    'srv-sawaco',
    NULL,
    'SW987654',
    185000,
    N'{"bill_type":"WATER","provider_code":"SAWACO","customer_name":"Nguyễn Văn A","customer_address":"123 Nguyễn Huệ, Quận 1, TP.HCM","billing_period":"11/2026"}',
    'PENDING'
);
GO

-- 10. Seed bank_branches (Chi nhánh & ATM ngân hàng phục vụ Navigation & AI Chatbot)
INSERT INTO dbo.bank_branches (branch_id, name, branch_type, address, latitude, longitude, opening_hours, status) VALUES
('br-q1-saigon',    N'Chi nhánh Sài Gòn (Quận 1)',           'BOTH',   N'2 Công trường Mê Linh, Bến Nghé, Quận 1, TP.HCM',         10.776889, 106.706139, '08:00 - 17:00', 'OPEN'),
('br-q1-benthanh',  N'Chi nhánh Bến Thành',                  'BRANCH', N'136 Hàm Nghi, Phường Bến Thành, Quận 1, TP.HCM',          10.770920, 106.698350, '08:00 - 17:00', 'OPEN'),
('br-q1-atm-nh',    N'Cụm ATM Phố đi bộ Nguyễn Huệ',         'ATM',    N'68 Nguyễn Huệ, Phường Bến Nghé, Quận 1, TP.HCM',          10.774510, 106.703920, '24/7',          'OPEN'),
('br-q7-phumyhung', N'Chi nhánh Quận 7 (Phú Mỹ Hưng)',       'BOTH',   N'105 Nguyễn Văn Linh, Tân Phong, Quận 7, TP.HCM',          10.729150, 106.709820, '08:00 - 17:00', 'OPEN'),
('br-q7-atm-cres',  N'Cây ATM Crescent Mall Quận 7',         'ATM',    N'101 Tôn Dật Tiên, Tân Phú, Quận 7, TP.HCM',               10.728600, 106.718500, '24/7',          'OPEN'),
('br-tb-conghoa',   N'Chi nhánh Tân Bình',                   'BOTH',   N'250 Cộng Hòa, Phường 12, Quận Tân Bình, TP.HCM',          10.801650, 106.647210, '08:00 - 16:30', 'OPEN'),
('br-bt-landmark',  N'PGD Vinhomes Central Park Bình Thạnh', 'BOTH',   N'208 Nguyễn Hữu Cảnh, Phường 22, Quận Bình Thạnh, TP.HCM', 10.795120, 106.721840, '08:30 - 17:30', 'OPEN'),
('br-td-gigamall',  N'Chi nhánh Thủ Đức (Phạm Văn Đồng)',    'BOTH',   N'240 Phạm Văn Đồng, Hiệp Bình Chánh, TP. Thủ Đức, TP.HCM', 10.827890, 106.721450, '08:00 - 17:00', 'OPEN');
GO
