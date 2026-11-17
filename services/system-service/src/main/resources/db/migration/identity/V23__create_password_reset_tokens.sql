-- Quên mật khẩu: token đặt lại dùng một lần, chỉ lưu hash SHA-256 (token thô chỉ gửi cho người dùng qua kênh ngoài).
-- Bảng toàn cục như credentials/refresh_tokens (không có tenant_id, không RLS): yêu cầu công khai chưa biết tenant.
-- Có TTL (expires_at) và đánh dấu đã dùng (used_at); token thừa hạn do job dọn dẹp xóa sau (chưa có), không ảnh hưởng đúng đắn.
CREATE TABLE password_reset_tokens (
    id         UUID                     PRIMARY KEY,
    user_id    UUID                     NOT NULL REFERENCES credentials (user_id) ON DELETE CASCADE,
    token_hash VARCHAR(64)              NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at    TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Giới hạn tần suất theo tài khoản (đếm token mới tạo gần đây) và vô hiệu token cũ của người dùng
CREATE INDEX idx_password_reset_tokens_user_created ON password_reset_tokens (user_id, created_at);
