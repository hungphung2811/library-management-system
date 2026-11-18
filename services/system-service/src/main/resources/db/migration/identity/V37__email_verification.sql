-- Xác minh email bằng OTP cho tài khoản khách tự đăng ký (audit A3, chống đăng ký giả hàng loạt).
-- credentials.email_verified_at: NULL = chưa xác minh. DEFAULT now() có chủ đích: mọi tài khoản hiện có (nhân viên, khách
-- cũ) và mọi đường tạo tài khoản khác ngoài tự đăng ký đều coi như đã xác minh nên không ai bị khóa; chỉ luồng
-- tự đăng ký của storefront ghi NULL tường minh khi bật lms.identity.email-verification.enabled.
ALTER TABLE credentials ADD COLUMN email_verified_at TIMESTAMP WITH TIME ZONE DEFAULT now();

-- Một dòng OTP đang hiệu lực cho mỗi người dùng (gửi lại thì ghi đè). Bảng toàn cục như credentials/password_reset_tokens
-- (tài khoản là toàn cục, yêu cầu công khai chưa biết tenant): không tenant_id, không RLS. KHÔNG lưu OTP thô, chỉ
-- HMAC-SHA256 có muối riêng từng dòng (otp_salt).
CREATE TABLE email_verifications (
    user_id          UUID                     PRIMARY KEY REFERENCES credentials (user_id) ON DELETE CASCADE,
    otp_hash         VARCHAR(64)              NOT NULL,
    otp_salt         VARCHAR(64)              NOT NULL,
    expires_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    attempts         INTEGER                  NOT NULL DEFAULT 0,
    last_sent_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    sends_in_window  INTEGER                  NOT NULL DEFAULT 1,
    window_started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at      TIMESTAMP WITH TIME ZONE
);
