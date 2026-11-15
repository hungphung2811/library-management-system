-- Callback Flyway afterMigrate: chạy sau MỌI lần migrate (kể cả khi không có script mới), không ghi vào
-- flyway_schema_history. Cấp quyền dữ liệu cho vai trò chạy ứng dụng catalog_app (không phải chủ bảng,
-- NOBYPASSRLS nên chịu RLS). Idempotent. Vai trò do infrastructure/postgres/init-databases.sql tạo; nếu chưa có thì
-- chỉ cảnh báo. Tạo vai trò muộn (volume cũ) thì chạy lại migration-service là đủ.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'catalog_app') THEN
        GRANT USAGE ON SCHEMA public TO catalog_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO catalog_app;
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO catalog_app;
        -- Bảng/sequence do migration sau (cùng vai trò chạy migration) tạo cũng được cấp
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO catalog_app;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO catalog_app;
        -- Sổ cái migration chỉ migration-service được ghi
        REVOKE ALL ON flyway_schema_history FROM catalog_app;
    ELSE
        RAISE WARNING 'Vai trò catalog_app chưa tồn tại: bỏ qua cấp quyền (tạo vai trò rồi chạy lại migration)';
    END IF;
END
$$;
