-- Đăng nhập ngoài (OIDC). Khóa liên kết là cặp (provider, subject), không dùng email làm khóa.
CREATE TABLE external_logins (
    id           UUID                     PRIMARY KEY,
    user_id      UUID                     NOT NULL,
    provider     VARCHAR(50)              NOT NULL,
    subject      VARCHAR(255)             NOT NULL,
    email        VARCHAR(255),
    linked_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    last_used_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_external_logins_subject UNIQUE (provider, subject),
    CONSTRAINT uq_external_logins_user_provider UNIQUE (user_id, provider)
);

-- Mã dùng một lần để frontend đổi lấy token sau khi quay về từ IdP (không đặt token lên URL). Chỉ lưu băm.
CREATE TABLE external_sign_in_tickets (
    code_hash  VARCHAR(64)              PRIMARY KEY,
    user_id    UUID                     NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used       BOOLEAN                  NOT NULL DEFAULT FALSE
);
