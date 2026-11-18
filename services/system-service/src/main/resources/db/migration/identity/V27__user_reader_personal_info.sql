-- Thông tin cá nhân tùy chọn của người dùng (users, toàn cục) và độc giả (reader_profiles, RLS giữ nguyên).
-- Chỉ DDL, mọi cột nullable, không backfill. Dữ liệu cá nhân: không đưa vào log/audit/event.

ALTER TABLE users
    ADD COLUMN phone         VARCHAR(20),
    ADD COLUMN date_of_birth DATE,
    ADD COLUMN gender        VARCHAR(10),
    ADD COLUMN address       VARCHAR(500),
    ADD COLUMN avatar_url    VARCHAR(500),
    ADD COLUMN job_title     VARCHAR(100),
    ADD COLUMN employee_code VARCHAR(32);

ALTER TABLE users
    ADD CONSTRAINT ck_users_phone CHECK (phone IS NULL OR phone ~ '^[0-9+ -]{1,20}$'),
    ADD CONSTRAINT ck_users_gender CHECK (gender IS NULL OR gender IN ('MALE', 'FEMALE', 'OTHER')),
    ADD CONSTRAINT ck_users_date_of_birth CHECK (date_of_birth IS NULL OR date_of_birth >= DATE '1900-01-01'),
    ADD CONSTRAINT ck_users_avatar_url CHECK (avatar_url IS NULL OR avatar_url ~* '^https?://');

ALTER TABLE reader_profiles
    ADD COLUMN phone            VARCHAR(20),
    ADD COLUMN date_of_birth    DATE,
    ADD COLUMN gender           VARCHAR(10),
    ADD COLUMN address          VARCHAR(500),
    ADD COLUMN faculty          VARCHAR(150),
    ADD COLUMN class_name       VARCHAR(50),
    ADD COLUMN member_code      VARCHAR(32),
    ADD COLUMN card_issued_date DATE;

ALTER TABLE reader_profiles
    ADD CONSTRAINT ck_reader_profiles_phone CHECK (phone IS NULL OR phone ~ '^[0-9+ -]{1,20}$'),
    ADD CONSTRAINT ck_reader_profiles_gender CHECK (gender IS NULL OR gender IN ('MALE', 'FEMALE', 'OTHER')),
    ADD CONSTRAINT ck_reader_profiles_date_of_birth CHECK (date_of_birth IS NULL OR date_of_birth >= DATE '1900-01-01'),
    ADD CONSTRAINT ck_reader_profiles_card_issued CHECK (card_issued_date IS NULL OR card_issued_date <= card_expiry_date);
