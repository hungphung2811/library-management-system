-- Mỗi service sở hữu một database riêng.
-- Hai vai trò cho mỗi DB:
--   <svc>      : chủ DB và chủ bảng, chỉ system-service dùng (chạy Flyway).
--   <svc>_app  : service dùng lúc chạy. NOSUPERUSER NOBYPASSRLS, không sở hữu bảng nên chịu Row-Level Security.
--                Quyền SELECT/INSERT/UPDATE/DELETE do callback afterMigrate.sql của system-service cấp sau mỗi
--                lần migrate (kể cả khi không có script mới).
-- Script này chỉ chạy khi volume postgres còn trống. Volume cũ: `docker compose down -v`, hoặc chạy tay các lệnh
-- CREATE USER ..._app bên dưới rồi khởi động lại/chạy lại system-service (afterMigrate tự cấp quyền, không cần
-- GRANT tay).
CREATE USER identity WITH PASSWORD 'identity';
CREATE DATABASE identity_db OWNER identity;
CREATE USER identity_app WITH PASSWORD 'identity_app' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;

CREATE USER circulation WITH PASSWORD 'circulation';
CREATE DATABASE circulation_db OWNER circulation;
CREATE USER circulation_app WITH PASSWORD 'circulation_app' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;

-- support-service, ngữ cảnh audit: PostgreSQL (AUDIT_DB) cho audit log; bản thô của event nằm ở MongoDB (LOG_DB).
CREATE USER audit WITH PASSWORD 'audit';
CREATE DATABASE audit_db OWNER audit;
CREATE USER audit_app WITH PASSWORD 'audit_app' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;

CREATE USER catalog WITH PASSWORD 'catalog';
CREATE DATABASE catalog_db OWNER catalog;
CREATE USER catalog_app WITH PASSWORD 'catalog_app' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;

-- system-service, ngữ cảnh menu (ADR-0007): system_db do chính runner migration của system-service migrate (target
-- `system`). Vai trò `system` là chủ DB (chỉ runner dùng); nếu CREATE USER system lỗi thì đổi tên thành system_owner
-- và đặt SYSTEM_DB_USERNAME tương ứng.
CREATE USER system WITH PASSWORD 'system';
CREATE DATABASE system_db OWNER system;
CREATE USER system_app WITH PASSWORD 'system_app' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
