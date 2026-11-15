-- Refresh token gắn tenant đã chọn lúc đăng nhập/đổi tenant; làm mới giữ nguyên tenant đó.
-- Cột thường, không RLS (bảng refresh_tokens đọc trước khi biết tenant, lúc làm mới phiên).
-- tenant_id NULL: phiên nền tảng của platform admin (platform_session = TRUE) hoặc token phát hành trước script này
-- (platform_session = FALSE): loại sau bị từ chối khi làm mới, người dùng đăng nhập lại.
ALTER TABLE refresh_tokens ADD COLUMN tenant_id UUID;
ALTER TABLE refresh_tokens ADD COLUMN platform_session BOOLEAN NOT NULL DEFAULT FALSE;
