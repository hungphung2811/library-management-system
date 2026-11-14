-- Hồ sơ độc giả (UC10): loại độc giả, hạn thẻ, trạng thái khóa. Khóa chính là userId của tài khoản đăng nhập.
CREATE TABLE reader_profiles (
    user_id          UUID         PRIMARY KEY,
    card_no          VARCHAR(32)  NOT NULL UNIQUE,
    full_name        VARCHAR(255) NOT NULL,
    email            VARCHAR(255) NOT NULL,
    reader_type      VARCHAR(20)  NOT NULL CHECK (reader_type IN ('STUDENT', 'LECTURER')),
    card_expiry_date DATE         NOT NULL,
    status           VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE', 'LOCKED'))
);
