-- Mỗi service sở hữu một database riêng.
CREATE USER identity WITH PASSWORD 'identity';
CREATE DATABASE identity_db OWNER identity;

CREATE USER circulation WITH PASSWORD 'circulation';
CREATE DATABASE circulation_db OWNER circulation;

-- support-service, ngữ cảnh audit: PostgreSQL (AUDIT_DB) cho audit log; bản thô của event nằm ở MongoDB (LOG_DB).
CREATE USER audit WITH PASSWORD 'audit';
CREATE DATABASE audit_db OWNER audit;

CREATE USER catalog WITH PASSWORD 'catalog';
CREATE DATABASE catalog_db OWNER catalog;
