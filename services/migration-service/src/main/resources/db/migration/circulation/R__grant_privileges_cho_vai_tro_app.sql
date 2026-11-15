-- Cấp quyền dữ liệu cho vai trò chạy ứng dụng circulation_app (không phải chủ bảng, NOBYPASSRLS nên chịu RLS).
-- Repeatable: Flyway chạy lại khi nội dung file đổi. Vai trò do infrastructure/postgres/init-databases.sql tạo;
-- nếu chưa có (DB cũ, test chỉ chạy migration) thì bỏ qua. Tạo vai trò sau khi đã migrate thì sửa file này
-- (ví dụ tăng số phiên bản ở dòng dưới) hoặc chạy tay các lệnh GRANT bên dưới.
-- Phiên bản: 1
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'circulation_app') THEN
        GRANT USAGE ON SCHEMA public TO circulation_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO circulation_app;
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO circulation_app;
        -- Bảng/sequence do migration sau (cùng vai trò chạy migration) tạo cũng được cấp
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO circulation_app;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO circulation_app;
    END IF;
END
$$;
