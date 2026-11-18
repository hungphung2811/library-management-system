-- ADR-0031 goi F2: dieu phoi xuat du lieu tenant (context identity.tenantdata). Chi tao hai bang cua tinh nang xuat;
-- bang vong doi/purge de goi G1/G2 (khong sua file nay).
-- Bang TOAN CUC, KHONG RLS (job nen quet moi tenant; nam trong TenantArchitectureRules.TENANT_EXEMPT_TABLES): chong IDOR
-- bang loc (tenant_id, id) tuong minh o use case + test hai tenant. Khong ghi du lieu vao bang RLS nen khong can set_config.
CREATE TABLE tenant_data_exports (
    id                    UUID                     PRIMARY KEY,
    tenant_id             UUID                     NOT NULL REFERENCES tenants (id),
    status                VARCHAR(16)              NOT NULL
                                                   CHECK (status IN ('PENDING', 'RUNNING', 'READY', 'FAILED', 'EXPIRED')),
    requested_by          UUID                     NOT NULL,
    requested_by_platform BOOLEAN                  NOT NULL DEFAULT FALSE,
    reason                VARCHAR(500),
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at            TIMESTAMP WITH TIME ZONE,
    finished_at           TIMESTAMP WITH TIME ZONE,
    expires_at            TIMESTAMP WITH TIME ZONE,
    parts_total           INTEGER                  NOT NULL DEFAULT 0,
    parts_done            INTEGER                  NOT NULL DEFAULT 0,
    size_bytes            BIGINT                   NOT NULL DEFAULT 0,
    sha256                VARCHAR(64),
    storage_key           VARCHAR(200),                                     -- khoa luu tru tat dinh, KHONG tra ra ngoai
    error_code            VARCHAR(40),                                      -- ma chung, khong luu thong diep loi tho
    download_count        INTEGER                  NOT NULL DEFAULT 0,
    last_downloaded_at    TIMESTAMP WITH TIME ZONE
);
-- Mot yeu cau dang chay moi tenant (409 EXPORT_IN_PROGRESS khi dua nhau tao).
CREATE UNIQUE INDEX uq_tenant_data_exports_in_progress ON tenant_data_exports (tenant_id)
    WHERE status IN ('PENDING', 'RUNNING');
-- Danh sach theo tenant (moi nhat truoc) va kiem gioi han 1 lan thanh cong/24 gio: tenant_id dung dau.
CREATE INDEX idx_tenant_data_exports_tenant_created ON tenant_data_exports (tenant_id, created_at DESC);
-- Job: lay PENDING cu nhat; phat hien RUNNING ket; don READY het han.
CREATE INDEX idx_tenant_data_exports_status_created ON tenant_data_exports (status, created_at);
CREATE INDEX idx_tenant_data_exports_ready_expires ON tenant_data_exports (expires_at) WHERE status = 'READY';

CREATE TABLE tenant_data_export_parts (
    export_id   UUID                     NOT NULL REFERENCES tenant_data_exports (id) ON DELETE CASCADE,
    service     VARCHAR(40)              NOT NULL,
    status      VARCHAR(16)              NOT NULL CHECK (status IN ('PENDING', 'DONE', 'FAILED')),
    attempts    INTEGER                  NOT NULL DEFAULT 0,
    tables_json TEXT                     NOT NULL DEFAULT '[]',              -- [{name,file,rows,bytes,sha256,columns}]
    error_code  VARCHAR(40),
    finished_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (export_id, service)
);
