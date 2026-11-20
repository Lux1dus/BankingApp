const sql = require('mssql');

const baseConfig = {
    user: process.env.DB_USER || 'sa',
    password: process.env.DB_PASSWORD || 'YourStrong!Passw0rd',
    server: process.env.DB_HOST || 'db',
    port: parseInt(process.env.DB_PORT, 10) || 1433,
    options: {
        encrypt: true,
        trustServerCertificate: true // Bắt buộc cho SQL Server chạy trong Docker / Local
    },
    pool: {
        max: 10,
        min: 0,
        idleTimeoutMillis: 30000
    }
};

const DB_NAME = process.env.DB_NAME || 'BankingApp';

const dbConfig = {
    ...baseConfig,
    database: DB_NAME
};

let poolPromise = null;
let isInitialized = false;

/**
 * Tự động khởi tạo Database, 14 Bảng chuẩn theo ERD_new.html & Class_Unified.html và Seed Data
 */
const ensureSchemaAndSeed = async (pool) => {
    if (isInitialized) return;

    await pool.request().query(`
        -- 1. [M1 -> SHARED] users
        IF OBJECT_ID(N'dbo.users', N'U') IS NULL
        BEGIN
            CREATE TABLE dbo.users (
                user_id        NVARCHAR(36)  NOT NULL CONSTRAINT PK_users PRIMARY KEY DEFAULT (CONVERT(NVARCHAR(36), NEWID())),
                username       NVARCHAR(50)  NOT NULL CONSTRAINT UQ_users_username UNIQUE,
                password_hash  NVARCHAR(255) NOT NULL,
                full_name      NVARCHAR(150) NOT NULL,
                email          NVARCHAR(150) NULL,
                phone_number   NVARCHAR(20)  NULL,
                role           NVARCHAR(20)  NOT NULL DEFAULT ('CUSTOMER'),
                status         NVARCHAR(20)  NOT NULL DEFAULT ('ACTIVE'),
                created_at     DATETIME2(0)  NOT NULL DEFAULT (SYSUTCDATETIME())
            );
        END;

        -- 2. [M1 -> SHARED] accounts
        IF OBJECT_ID(N'dbo.accounts', N'U') IS NULL
        BEGIN
            CREATE TABLE dbo.accounts (
                account_id     NVARCHAR(36)  NOT NULL CONSTRAINT PK_accounts PRIMARY KEY DEFAULT (CONVERT(NVARCHAR(36), NEWID())),
                account_number NVARCHAR(20)  NOT NULL CONSTRAINT UQ_accounts_number UNIQUE,
                user_id        NVARCHAR(36)  NOT NULL REFERENCES dbo.users(user_id),
                account_type   NVARCHAR(20)  NOT NULL,
                balance        DECIMAL(18,2) NOT NULL DEFAULT (0),
                currency       NVARCHAR(10)  NOT NULL DEFAULT ('VND'),
                status         NVARCHAR(20)  NOT NULL DEFAULT ('ACTIVE'),
                created_at     DATETIME2(0)  NOT NULL DEFAULT (SYSUTCDATETIME())
            );
        END;

        -- 3. [M1 - Internal] saving_rates
        IF OBJECT_ID(N'dbo.saving_rates', N'U') IS NULL
        BEGIN
            CREATE TABLE dbo.saving_rates (
                rate_id               NVARCHAR(36)  NOT NULL CONSTRAINT PK_saving_rates PRIMARY KEY DEFAULT (CONVERT(NVARCHAR(36), NEWID())),
                term_months           INT           NOT NULL,
                interest_rate         DECIMAL(9,4)  NOT NULL,
                updated_by_officer_id NVARCHAR(36)  NULL REFERENCES dbo.users(user_id),
                updated_at            DATETIME2(0)  NOT NULL DEFAULT (SYSUTCDATETIME())
            );
        END;

        -- 4. [M1 - Internal] saving_details
        IF OBJECT_ID(N'dbo.saving_details', N'U') IS NULL
        BEGIN
            CREATE TABLE dbo.saving_details (
                account_id      NVARCHAR(36)  NOT NULL CONSTRAINT PK_saving_details PRIMARY KEY REFERENCES dbo.accounts(account_id),
                rate_id         NVARCHAR(36)  NOT NULL REFERENCES dbo.saving_rates(rate_id),
                initial_deposit DECIMAL(18,2) NOT NULL,
                start_date      DATETIME2(0)  NOT NULL,
                maturity_date   DATETIME2(0)  NOT NULL,
                monthly_profit  DECIMAL(18,2) NOT NULL DEFAULT (0),
                auto_renew      BIT           NOT NULL DEFAULT (0)
            );
        END;

        -- 5. [M1 - Internal] mortgage_details
        IF OBJECT_ID(N'dbo.mortgage_details', N'U') IS NULL
        BEGIN
            CREATE TABLE dbo.mortgage_details (
                account_id        NVARCHAR(36)  NOT NULL CONSTRAINT PK_mortgage_details PRIMARY KEY REFERENCES dbo.accounts(account_id),
                total_loan        DECIMAL(18,2) NOT NULL,
                remaining_debt    DECIMAL(18,2) NOT NULL,
                interest_rate     DECIMAL(9,4)  NOT NULL,
                payment_frequency NVARCHAR(20)  NOT NULL,
                payment_amount    DECIMAL(18,2) NOT NULL,
                next_due_date     DATETIME2(0)  NULL
            );
        END;

        -- 6. [M2 - Internal] ekyc_verifications
        IF OBJECT_ID(N'dbo.ekyc_verifications', N'U') IS NULL
        BEGIN
            CREATE TABLE dbo.ekyc_verifications (
                ekyc_id            NVARCHAR(36)  NOT NULL CONSTRAINT PK_ekyc_verifications PRIMARY KEY DEFAULT (CONVERT(NVARCHAR(36), NEWID())),
                user_id            NVARCHAR(36)  NOT NULL REFERENCES dbo.users(user_id),
                id_card_number     NVARCHAR(20)  NOT NULL,
                document_photo_url NVARCHAR(500) NOT NULL,
                face_photo_url     NVARCHAR(500) NOT NULL,
                status             NVARCHAR(20)  NOT NULL DEFAULT ('PENDING'),
                reviewed_by        NVARCHAR(36)  NULL REFERENCES dbo.users(user_id),
                reviewed_at        DATETIME2(0)  NULL,
                rejection_reason   NVARCHAR(500) NULL,
                created_at         DATETIME2(0)  NOT NULL DEFAULT (SYSUTCDATETIME()),
                updated_at         DATETIME2(0)  NOT NULL DEFAULT (SYSUTCDATETIME())
            );
        END;

        -- 7. [M2 - Internal] security_audit_logs
        IF OBJECT_ID(N'dbo.security_audit_logs', N'U') IS NULL
        BEGIN
            CREATE TABLE dbo.security_audit_logs (
                log_id      NVARCHAR(36)  NOT NULL CONSTRAINT PK_security_audit_logs PRIMARY KEY DEFAULT (CONVERT(NVARCHAR(36), NEWID())),
                actor_id    NVARCHAR(36)  NOT NULL REFERENCES dbo.users(user_id),
                action      NVARCHAR(100) NOT NULL,
                entity_name NVARCHAR(100) NOT NULL,
                entity_id   NVARCHAR(100) NOT NULL,
                status      NVARCHAR(50)  NOT NULL,
                details     NVARCHAR(MAX) NULL,
                ip_address  NVARCHAR(50)  NULL,
                timestamp   DATETIME2(0)  NOT NULL DEFAULT (SYSUTCDATETIME())
            );
        END;

        -- 8. [M2 -> SHARED] transactions
        IF OBJECT_ID(N'dbo.transactions', N'U') IS NULL
        BEGIN
            CREATE TABLE dbo.transactions (
                transaction_id        NVARCHAR(36)  NOT NULL CONSTRAINT PK_transactions PRIMARY KEY DEFAULT (CONVERT(NVARCHAR(36), NEWID())),
                user_id               NVARCHAR(36)  NOT NULL REFERENCES dbo.users(user_id),
                transaction_type      NVARCHAR(30)  NOT NULL,
                source_account_id     NVARCHAR(36)  NULL REFERENCES dbo.accounts(account_id),
                target_account_id     NVARCHAR(36)  NULL REFERENCES dbo.accounts(account_id),
                target_account_number NVARCHAR(36)  NULL,
                target_bank_code      NVARCHAR(20)  NULL,
                receiver_name         NVARCHAR(150) NULL,
                amount                DECIMAL(18,2) NOT NULL,
                currency              NVARCHAR(10)  NOT NULL DEFAULT ('VND'),
                description           NVARCHAR(500) NULL,
                status                NVARCHAR(20)  NOT NULL DEFAULT ('PENDING'),
                is_high_value         BIT           NOT NULL DEFAULT (0),
                is_2fa_verified       BIT           NOT NULL DEFAULT (0),
                is_biometric_verified BIT           NOT NULL DEFAULT (0),
                created_at            DATETIME2(0)  NOT NULL DEFAULT (SYSUTCDATETIME()),
                completed_at          DATETIME2(0)  NULL
            );
        END
        ELSE
        BEGIN
            IF COL_LENGTH('dbo.transactions', 'user_id') IS NULL
                ALTER TABLE dbo.transactions ADD user_id NVARCHAR(36) NULL;
            IF COL_LENGTH('dbo.transactions', 'transaction_type') IS NULL
                ALTER TABLE dbo.transactions ADD transaction_type NVARCHAR(30) NULL;
            IF COL_LENGTH('dbo.transactions', 'target_account_id') IS NULL
                ALTER TABLE dbo.transactions ADD target_account_id NVARCHAR(36) NULL;
            IF COL_LENGTH('dbo.transactions', 'target_account_number') IS NULL
                ALTER TABLE dbo.transactions ADD target_account_number NVARCHAR(36) NULL;
            IF COL_LENGTH('dbo.transactions', 'target_bank_code') IS NULL
                ALTER TABLE dbo.transactions ADD target_bank_code NVARCHAR(20) NULL;
            IF COL_LENGTH('dbo.transactions', 'currency') IS NULL
                ALTER TABLE dbo.transactions ADD currency NVARCHAR(10) NOT NULL DEFAULT ('VND');
            IF COL_LENGTH('dbo.transactions', 'description') IS NULL
                ALTER TABLE dbo.transactions ADD description NVARCHAR(500) NULL;
            IF COL_LENGTH('dbo.transactions', 'is_high_value') IS NULL
                ALTER TABLE dbo.transactions ADD is_high_value BIT NOT NULL DEFAULT (0);
            IF COL_LENGTH('dbo.transactions', 'completed_at') IS NULL
                ALTER TABLE dbo.transactions ADD completed_at DATETIME2(0) NULL;
        END;

        -- 9. [M2 - Internal] otp_challenges
        IF OBJECT_ID(N'dbo.otp_challenges', N'U') IS NULL
        BEGIN
            CREATE TABLE dbo.otp_challenges (
                challenge_id   NVARCHAR(36)  NOT NULL CONSTRAINT PK_otp_challenges PRIMARY KEY DEFAULT (CONVERT(NVARCHAR(36), NEWID())),
                transaction_id NVARCHAR(36)  NOT NULL REFERENCES dbo.transactions(transaction_id),
                user_id        NVARCHAR(36)  NOT NULL REFERENCES dbo.users(user_id),
                otp_hash       NVARCHAR(255) NOT NULL,
                channel        NVARCHAR(20)  NOT NULL DEFAULT ('SMS'),
                attempts_count INT           NOT NULL DEFAULT (0),
                max_attempts   INT           NOT NULL DEFAULT (3),
                is_used        BIT           NOT NULL DEFAULT (0),
                expires_at     DATETIME2(0)  NOT NULL,
                status         NVARCHAR(20)  NOT NULL DEFAULT ('PENDING'),
                created_at     DATETIME2(0)  NOT NULL DEFAULT (SYSUTCDATETIME())
            );
        END;

        -- 10. [M3 - Internal] utility_services
        IF OBJECT_ID(N'dbo.utility_services', N'U') IS NULL
        BEGIN
            CREATE TABLE dbo.utility_services (
                service_id         NVARCHAR(36)  NOT NULL CONSTRAINT PK_utility_services PRIMARY KEY DEFAULT (CONVERT(NVARCHAR(36), NEWID())),
                provider_code      NVARCHAR(50)  NOT NULL CONSTRAINT UQ_us_provider UNIQUE,
                service_name       NVARCHAR(150) NOT NULL,
                service_type       NVARCHAR(20)  NOT NULL,
                aggregator_gateway NVARCHAR(20)  NOT NULL DEFAULT ('VNPAY'),
                status             NVARCHAR(20)  NOT NULL DEFAULT ('ACTIVE'),
                created_at         DATETIME2(0)  NOT NULL DEFAULT (SYSUTCDATETIME())
            );
        END;

        -- 11. [M3 - Internal] utility_orders
        IF OBJECT_ID(N'dbo.utility_orders', N'U') IS NULL
        BEGIN
            CREATE TABLE dbo.utility_orders (
                order_id             NVARCHAR(36)  NOT NULL CONSTRAINT PK_utility_orders PRIMARY KEY DEFAULT (CONVERT(NVARCHAR(36), NEWID())),
                user_id              NVARCHAR(36)  NOT NULL REFERENCES dbo.users(user_id),
                service_id           NVARCHAR(36)  NOT NULL REFERENCES dbo.utility_services(service_id),
                transaction_id       NVARCHAR(36)  NULL REFERENCES dbo.transactions(transaction_id),
                provider_customer_id NVARCHAR(100) NOT NULL,
                total_amount         DECIMAL(18,2) NOT NULL,
                metadata_payload     NVARCHAR(MAX) NULL,
                status               NVARCHAR(20)  NOT NULL DEFAULT ('PENDING'),
                hold_expires_at      DATETIME2(0)  NULL,
                created_at           DATETIME2(0)  NOT NULL DEFAULT (SYSUTCDATETIME())
            );
        END
        ELSE
        BEGIN
            IF COL_LENGTH('dbo.utility_orders', 'hold_expires_at') IS NULL
                ALTER TABLE dbo.utility_orders ADD hold_expires_at DATETIME2(0) NULL;
        END;

        -- 12. [M3 - Internal] bank_branches
        IF OBJECT_ID(N'dbo.bank_branches', N'U') IS NULL
        BEGIN
            CREATE TABLE dbo.bank_branches (
                branch_id     NVARCHAR(36)  NOT NULL CONSTRAINT PK_bank_branches PRIMARY KEY DEFAULT (CONVERT(NVARCHAR(36), NEWID())),
                name          NVARCHAR(150) NOT NULL,
                branch_type   NVARCHAR(20)  NOT NULL DEFAULT ('BRANCH'),
                address       NVARCHAR(300) NOT NULL,
                latitude      FLOAT         NOT NULL,
                longitude     FLOAT         NOT NULL,
                opening_hours NVARCHAR(100) NULL,
                status        NVARCHAR(20)  NOT NULL DEFAULT ('OPEN')
            );
        END
        ELSE
        BEGIN
            IF COL_LENGTH('dbo.bank_branches', 'branch_type') IS NULL
                ALTER TABLE dbo.bank_branches ADD branch_type NVARCHAR(20) NOT NULL DEFAULT ('BRANCH');
            IF COL_LENGTH('dbo.bank_branches', 'status') IS NULL
                ALTER TABLE dbo.bank_branches ADD status NVARCHAR(20) NOT NULL DEFAULT ('OPEN');
        END;

        -- 13. [M3 - Internal] chatbot_sessions
        IF OBJECT_ID(N'dbo.chatbot_sessions', N'U') IS NULL
        BEGIN
            CREATE TABLE dbo.chatbot_sessions (
                session_id NVARCHAR(36) NOT NULL CONSTRAINT PK_chatbot_sessions PRIMARY KEY DEFAULT (CONVERT(NVARCHAR(36), NEWID())),
                user_id    NVARCHAR(36) NOT NULL REFERENCES dbo.users(user_id),
                status     NVARCHAR(20) NOT NULL DEFAULT ('ACTIVE'),
                started_at DATETIME2(0) NOT NULL DEFAULT (SYSUTCDATETIME()),
                ended_at   DATETIME2(0) NULL
            );
        END;

        -- 14. [M3 - Internal] chat_messages
        IF OBJECT_ID(N'dbo.chat_messages', N'U') IS NULL
        BEGIN
            CREATE TABLE dbo.chat_messages (
                message_id              NVARCHAR(36)  NOT NULL CONSTRAINT PK_chat_messages PRIMARY KEY DEFAULT (CONVERT(NVARCHAR(36), NEWID())),
                session_id              NVARCHAR(36)  NOT NULL REFERENCES dbo.chatbot_sessions(session_id),
                sender                  NVARCHAR(10)  NOT NULL,
                input_type              NVARCHAR(20)  NOT NULL DEFAULT ('TEXT'),
                message_text            NVARCHAR(MAX) NOT NULL,
                detected_intent         NVARCHAR(50)  NULL,
                extracted_entities_json NVARCHAR(MAX) NULL,
                action_type             NVARCHAR(20)  NOT NULL DEFAULT ('NONE'),
                action_destination      NVARCHAR(200) NULL,
                action_params_json      NVARCHAR(MAX) NULL,
                created_at              DATETIME2(0)  NOT NULL DEFAULT (SYSUTCDATETIME())
            );
        END
        ELSE
        BEGIN
            IF COL_LENGTH('dbo.chat_messages', 'input_type') IS NULL
                ALTER TABLE dbo.chat_messages ADD input_type NVARCHAR(20) NOT NULL DEFAULT ('TEXT');
            IF COL_LENGTH('dbo.chat_messages', 'detected_intent') IS NULL
                ALTER TABLE dbo.chat_messages ADD detected_intent NVARCHAR(50) NULL;
            IF COL_LENGTH('dbo.chat_messages', 'extracted_entities_json') IS NULL
                ALTER TABLE dbo.chat_messages ADD extracted_entities_json NVARCHAR(MAX) NULL;
            IF COL_LENGTH('dbo.chat_messages', 'action_type') IS NULL
                ALTER TABLE dbo.chat_messages ADD action_type NVARCHAR(20) NOT NULL DEFAULT ('NONE');
            IF COL_LENGTH('dbo.chat_messages', 'action_destination') IS NULL
                ALTER TABLE dbo.chat_messages ADD action_destination NVARCHAR(200) NULL;
            IF COL_LENGTH('dbo.chat_messages', 'action_params_json') IS NULL
                ALTER TABLE dbo.chat_messages ADD action_params_json NVARCHAR(MAX) NULL;
        END;
    `);

    await seedDefaultData(pool);
    isInitialized = true;
};

const seedDefaultData = async (pool) => {
    await pool.request().query(`
        -- 1. Seed users
        IF NOT EXISTS (SELECT 1 FROM dbo.users WHERE username = 'nguyenvana')
        BEGIN
            INSERT INTO dbo.users (user_id, username, password_hash, full_name, email, phone_number, role, status)
            VALUES ('u1111111-1111-1111-1111-111111111111', 'nguyenvana', 'hashed_pw_123', N'Nguyễn Văn A', 'vana@example.com', '0987654321', 'CUSTOMER', 'ACTIVE');
        END;

        IF NOT EXISTS (SELECT 1 FROM dbo.users WHERE username = 'nguyenthib')
        BEGIN
            INSERT INTO dbo.users (user_id, username, password_hash, full_name, email, phone_number, role, status)
            VALUES ('u2222222-2222-2222-2222-222222222222', 'nguyenthib', 'hashed_pw_123', N'Nguyễn Thị B', 'me_b@example.com', '0901234567', 'CUSTOMER', 'ACTIVE');
        END;

        IF NOT EXISTS (SELECT 1 FROM dbo.users WHERE username = 'officer01')
        BEGIN
            INSERT INTO dbo.users (user_id, username, password_hash, full_name, email, phone_number, role, status)
            VALUES ('u9999999-9999-9999-9999-999999999999', 'officer01', 'hashed_pw_123', N'Trần Quản Lý', 'officer@bank.vn', '0912345678', 'OFFICER', 'ACTIVE');
        END;

        -- 2. Seed accounts
        IF NOT EXISTS (SELECT 1 FROM dbo.accounts WHERE account_number = '190088889999')
        BEGIN
            INSERT INTO dbo.accounts (account_id, account_number, user_id, account_type, balance, currency, status)
            VALUES ('a1b2c3d4-0000-0000-0000-000000000001', '190088889999', 'u1111111-1111-1111-1111-111111111111', 'CHECKING', 50000000, 'VND', 'ACTIVE');
        END;

        IF NOT EXISTS (SELECT 1 FROM dbo.accounts WHERE account_number = '190088887777')
        BEGIN
            INSERT INTO dbo.accounts (account_id, account_number, user_id, account_type, balance, currency, status)
            VALUES ('a1b2c3d4-0000-0000-0000-000000000002', '190088887777', 'u1111111-1111-1111-1111-111111111111', 'SAVING', 100000000, 'VND', 'ACTIVE');
        END;

        IF NOT EXISTS (SELECT 1 FROM dbo.accounts WHERE account_number = '0123456789')
        BEGIN
            INSERT INTO dbo.accounts (account_id, account_number, user_id, account_type, balance, currency, status)
            VALUES ('a1b2c3d4-0000-0000-0000-000000000003', '0123456789', 'u2222222-2222-2222-2222-222222222222', 'CHECKING', 15000000, 'VND', 'ACTIVE');
        END;

        IF NOT EXISTS (SELECT 1 FROM dbo.accounts WHERE account_number = '190088886666')
        BEGIN
            INSERT INTO dbo.accounts (account_id, account_number, user_id, account_type, balance, currency, status)
            VALUES ('a1b2c3d4-0000-0000-0000-000000000004', '190088886666', 'u1111111-1111-1111-1111-111111111111', 'MORTGAGE', 500000000, 'VND', 'ACTIVE');
        END;

        -- 3. Seed saving_rates
        IF NOT EXISTS (SELECT 1 FROM dbo.saving_rates)
        BEGIN
            INSERT INTO dbo.saving_rates (rate_id, term_months, interest_rate, updated_by_officer_id) VALUES
            ('rate-01m', 1,  3.2000, 'u9999999-9999-9999-9999-999999999999'),
            ('rate-03m', 3,  3.8000, 'u9999999-9999-9999-9999-999999999999'),
            ('rate-06m', 6,  5.1000, 'u9999999-9999-9999-9999-999999999999'),
            ('rate-12m', 12, 6.2000, 'u9999999-9999-9999-9999-999999999999'),
            ('rate-24m', 24, 6.5000, 'u9999999-9999-9999-9999-999999999999');
        END;

        -- 4. Seed ekyc_verifications
        IF NOT EXISTS (SELECT 1 FROM dbo.ekyc_verifications WHERE ekyc_id = 'ekyc-0001')
        BEGIN
            INSERT INTO dbo.ekyc_verifications (ekyc_id, user_id, id_card_number, document_photo_url, face_photo_url, status, reviewed_by, reviewed_at)
            VALUES ('ekyc-0001', 'u1111111-1111-1111-1111-111111111111', '079095001234', 'https://storage.bank.vn/ekyc/u1_cccd.jpg', 'https://storage.bank.vn/ekyc/u1_face.jpg', 'APPROVED', 'u9999999-9999-9999-9999-999999999999', SYSUTCDATETIME());
        END;

        -- 5. Seed security_audit_logs
        IF NOT EXISTS (SELECT 1 FROM dbo.security_audit_logs WHERE log_id = 'audit-0001')
        BEGIN
            INSERT INTO dbo.security_audit_logs (log_id, actor_id, action, entity_name, entity_id, status, details, ip_address)
            VALUES ('audit-0001', 'u9999999-9999-9999-9999-999999999999', 'APPROVE_EKYC', 'ekyc_verifications', 'ekyc-0001', 'SUCCESS', N'Phê duyệt hồ sơ định danh eKYC cho khách hàng Nguyễn Văn A', '10.0.0.1');
        END;

        -- 6. Seed utility_services
        IF NOT EXISTS (SELECT 1 FROM dbo.utility_services)
        BEGIN
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
        END;

        -- 7. Seed utility_orders (Hóa đơn Điện PE123456 850.000đ & Nước SW987654 185.000đ)
        IF NOT EXISTS (SELECT 1 FROM dbo.utility_orders WHERE provider_customer_id = 'PE123456')
        BEGIN
            INSERT INTO dbo.utility_orders (order_id, user_id, service_id, transaction_id, provider_customer_id, total_amount, metadata_payload, status)
            VALUES (
                'b9f1c2d3-0000-0000-0000-000000000001',
                'u1111111-1111-1111-1111-111111111111',
                'srv-evn-hcm',
                NULL,
                'PE123456',
                850000,
                N'{"bill_type":"ELECTRICITY","provider_code":"EVN_HCM","customer_name":"Nguyễn Văn A","customer_address":"123 Nguyễn Huệ, Quận 1, TP.HCM","billing_period":"11/2026"}',
                'PENDING'
            );
        END;

        IF NOT EXISTS (SELECT 1 FROM dbo.utility_orders WHERE provider_customer_id = 'SW987654')
        BEGIN
            INSERT INTO dbo.utility_orders (order_id, user_id, service_id, transaction_id, provider_customer_id, total_amount, metadata_payload, status)
            VALUES (
                'b9f1c2d3-0000-0000-0000-000000000002',
                'u1111111-1111-1111-1111-111111111111',
                'srv-sawaco',
                NULL,
                'SW987654',
                185000,
                N'{"bill_type":"WATER","provider_code":"SAWACO","customer_name":"Nguyễn Văn A","customer_address":"123 Nguyễn Huệ, Quận 1, TP.HCM","billing_period":"11/2026"}',
                'PENDING'
            );
        END;

        -- 8. Seed bank_branches
        IF NOT EXISTS (SELECT 1 FROM dbo.bank_branches)
        BEGIN
            INSERT INTO dbo.bank_branches (branch_id, name, branch_type, address, latitude, longitude, opening_hours, status) VALUES
            ('br-q1-saigon',    N'Chi nhánh Sài Gòn (Quận 1)',           'BOTH',   N'2 Công trường Mê Linh, Bến Nghé, Quận 1, TP.HCM',         10.776889, 106.706139, '08:00 - 17:00', 'OPEN'),
            ('br-q1-benthanh',  N'Chi nhánh Bến Thành',                  'BRANCH', N'136 Hàm Nghi, Phường Bến Thành, Quận 1, TP.HCM',          10.770920, 106.698350, '08:00 - 17:00', 'OPEN'),
            ('br-q1-atm-nh',    N'Cụm ATM Phố đi bộ Nguyễn Huệ',         'ATM',    N'68 Nguyễn Huệ, Phường Bến Nghé, Quận 1, TP.HCM',          10.774510, 106.703920, '24/7',          'OPEN'),
            ('br-q7-phumyhung', N'Chi nhánh Quận 7 (Phú Mỹ Hưng)',       'BOTH',   N'105 Nguyễn Văn Linh, Tân Phong, Quận 7, TP.HCM',          10.729150, 106.709820, '08:00 - 17:00', 'OPEN'),
            ('br-q7-atm-cres',  N'Cây ATM Crescent Mall Quận 7',         'ATM',    N'101 Tôn Dật Tiên, Tân Phú, Quận 7, TP.HCM',               10.728600, 106.718500, '24/7',          'OPEN'),
            ('br-tb-conghoa',   N'Chi nhánh Tân Bình',                   'BOTH',   N'250 Cộng Hòa, Phường 12, Quận Tân Bình, TP.HCM',          10.801650, 106.647210, '08:00 - 16:30', 'OPEN'),
            ('br-bt-landmark',  N'PGD Vinhomes Central Park Bình Thạnh', 'BOTH',   N'208 Nguyễn Hữu Cảnh, Phường 22, Quận Bình Thạnh, TP.HCM', 10.795120, 106.721840, '08:30 - 17:30', 'OPEN'),
            ('br-td-gigamall',  N'Chi nhánh Thủ Đức (Phạm Văn Đồng)',    'BOTH',   N'240 Phạm Văn Đồng, Hiệp Bình Chánh, TP. Thủ Đức, TP.HCM', 10.827890, 106.721450, '08:00 - 17:00', 'OPEN');
        END;
    `);
};

const getPool = async () => {
    if (!poolPromise) {
        poolPromise = (async () => {
            try {
                const masterPool = await new sql.ConnectionPool({ ...baseConfig, database: 'master' }).connect();
                await masterPool.request().query(`
                    IF DB_ID(N'${DB_NAME}') IS NULL
                        CREATE DATABASE [${DB_NAME}];
                `);
                await masterPool.close();

                const pool = await new sql.ConnectionPool(dbConfig).connect();
                console.log(`✅ Connected to SQL Server (${DB_NAME})`);
                await ensureSchemaAndSeed(pool);
                return pool;
            } catch (err) {
                console.error('❌ Database Connection Failed:', err);
                poolPromise = null;
                throw err;
            }
        })();
    }
    return poolPromise;
};

module.exports = { sql, getPool, seedDefaultData };
