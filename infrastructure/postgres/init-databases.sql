-- Mỗi service sở hữu một database riêng.
-- Hai vai trò cho mỗi DB:
--   <svc>      : chủ DB và chủ bảng, chỉ migration-service dùng (chạy Flyway).
--   <svc>_app  : service dùng lúc chạy. NOSUPERUSER NOBYPASSRLS, không sở hữu bảng nên chịu Row-Level Security.
--                Quyền SELECT/INSERT/UPDATE/DELETE do script R__grant_privileges_cho_vai_tro_app.sql của migration cấp.
-- Script này chỉ chạy khi volume postgres còn trống. Volume cũ: `docker compose down -v` hoặc chạy tay các lệnh
-- CREATE USER ..._app bên dưới rồi chạy lại migration-service.
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
