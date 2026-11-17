-- ADR-0017: họ phiên (session_id), siêu dữ liệu thiết bị tối thiểu, lý do thu hồi.
-- refresh_tokens là bảng toàn cục, không RLS (nằm trong TENANT_EXEMPT_TABLES), nên không cần app.tenant_id.
ALTER TABLE refresh_tokens ADD COLUMN session_id UUID;
ALTER TABLE refresh_tokens ADD COLUMN session_started_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE refresh_tokens ADD COLUMN device_summary VARCHAR(100);
ALTER TABLE refresh_tokens ADD COLUMN client_ip VARCHAR(45);
ALTER TABLE refresh_tokens ADD COLUMN revoked_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE refresh_tokens ADD COLUMN revoked_reason VARCHAR(20);

-- Dọn một lần: token hết hạn quá 1 ngày không còn tác dụng (kể cả phát hiện dùng lại)
DELETE FROM refresh_tokens WHERE expires_at < now() - INTERVAL '1 day';

-- Dữ liệu cũ: không dựng lại được chuỗi xoay vòng, mỗi dòng còn lại là một họ riêng
UPDATE refresh_tokens SET session_id = id, session_started_at = created_at WHERE session_id IS NULL;
UPDATE refresh_tokens SET revoked_at = created_at, revoked_reason = 'BULK' WHERE revoked AND revoked_at IS NULL;

ALTER TABLE refresh_tokens ALTER COLUMN session_id SET NOT NULL;
ALTER TABLE refresh_tokens ALTER COLUMN session_started_at SET NOT NULL;

CREATE INDEX idx_refresh_tokens_session_active ON refresh_tokens (session_id) WHERE revoked = false;
CREATE INDEX idx_refresh_tokens_user_active ON refresh_tokens (user_id) WHERE revoked = false;
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);
