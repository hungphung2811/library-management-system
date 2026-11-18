-- SaaS API Authentication, pha P1 (docs/architecture/SAAS_API_AUTHENTICATION_DESIGN.md mục 4.3, ADR-0027):
-- Application (website công khai / tích hợp backend), credential, scope được cấp, lịch sử chỉ thêm, và cột
-- application_id cho origin storefront (domain allowlist).
-- Bảng toàn cục, KHÔNG RLS, nằm trong TenantArchitectureRules.TENANT_EXEMPT_TABLES (D5): token endpoint (pha I2) và cấp
-- token theo site key (pha P2) tra client_id/site_key khi CHƯA biết tenant. Chống IDOR ở use case (lọc tenant_id + id).
-- Không ghi dữ liệu vào bảng RLS nên không cần set_config.
CREATE TABLE applications (
    id                    UUID                     PRIMARY KEY,           -- = client_id = sub của token integration
    tenant_id             UUID                     NOT NULL REFERENCES tenants (id),
    name                  VARCHAR(100)             NOT NULL,
    kind                  VARCHAR(20)              NOT NULL CHECK (kind IN ('PUBLIC_SITE', 'INTEGRATION')),
    status                VARCHAR(20)              NOT NULL DEFAULT 'ACTIVE'
                                                   CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
    site_key              VARCHAR(64)              UNIQUE,                -- định danh CÔNG KHAI, lưu nguyên văn
    rate_limit_per_minute INTEGER                  CHECK (rate_limit_per_minute > 0),
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    created_by            UUID,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_by            UUID,
    deleted_at            TIMESTAMP WITH TIME ZONE,
    version               BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT uq_applications_id_tenant UNIQUE (id, tenant_id),          -- đích của FK kép (chống lệch tenant)
    CONSTRAINT ck_applications_site_key CHECK ((kind = 'PUBLIC_SITE') = (site_key IS NOT NULL)),
    CONSTRAINT ck_applications_deleted  CHECK ((status = 'DELETED') = (deleted_at IS NOT NULL)),
    CONSTRAINT ck_applications_site_key_format CHECK (site_key IS NULL OR site_key ~ '^lms_pk_[A-Za-z0-9_-]{32,56}$')
);
-- Danh sách theo tenant (portal), lọc trạng thái, sắp theo ngày tạo: tenant_id đứng đầu.
CREATE INDEX idx_applications_tenant_status ON applications (tenant_id, status, created_at);
-- Tên duy nhất trong tenant cho ứng dụng chưa xóa.
CREATE UNIQUE INDEX uq_applications_tenant_name ON applications (tenant_id, lower(name)) WHERE status <> 'DELETED';

CREATE TABLE application_credentials (
    id             UUID                     PRIMARY KEY,
    application_id UUID                     NOT NULL,
    tenant_id      UUID                     NOT NULL,
    secret_hash    CHAR(64)                 NOT NULL,                     -- SHA-256 hex, KHÔNG lưu secret
    secret_hint    VARCHAR(8)               NOT NULL,                     -- 4 ký tự cuối để nhận diện
    status         VARCHAR(20)              NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    created_by     UUID,
    expires_at     TIMESTAMP WITH TIME ZONE,                              -- NULL = không hết hạn
    last_used_at   TIMESTAMP WITH TIME ZONE,
    revoked_at     TIMESTAMP WITH TIME ZONE,
    revoked_by     UUID,
    revoke_reason  VARCHAR(30)              CHECK (revoke_reason IN ('ROTATED', 'MANUAL', 'APPLICATION_DELETED', 'COMPROMISED')),
    CONSTRAINT fk_app_credentials_app FOREIGN KEY (application_id, tenant_id) REFERENCES applications (id, tenant_id),
    CONSTRAINT ck_app_credentials_revoked CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL)),
    CONSTRAINT ck_app_credentials_expiry  CHECK (expires_at IS NULL OR expires_at > created_at)
);
-- Token endpoint: credential ACTIVE của một application (<= 2 dòng); portal: liệt kê theo application.
-- Không đặt tenant_id đầu: bảng không RLS và mọi truy vấn đi theo application_id (đã duy nhất toàn cục).
CREATE INDEX idx_app_credentials_app_status ON application_credentials (application_id, status);

CREATE TABLE application_scopes (
    application_id UUID                     NOT NULL REFERENCES applications (id),
    scope          VARCHAR(40)              NOT NULL CHECK (scope IN (
                       'books:read', 'books:write', 'inventory:read', 'inventory:write',
                       'orders:read', 'orders:write', 'customers:read', 'customers:write')),
    granted_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    granted_by     UUID,
    PRIMARY KEY (application_id, scope)
);

-- Lịch sử chỉ thêm (mẫu tenant_storefront_origin_history, ADR-0013 2.9).
-- Lệch thiết kế 4.3.2: thêm DOMAIN_ADDED, DOMAIN_REMOVED vào danh sách action (quản lý domain allowlist cũng phải để lại dấu vết).
CREATE TABLE application_history (
    id             UUID                     PRIMARY KEY,
    tenant_id      UUID                     NOT NULL,
    application_id UUID                     NOT NULL,                     -- không FK: dòng gốc có thể đã xóa mềm
    credential_id  UUID,
    action         VARCHAR(40)              NOT NULL CHECK (action IN (
                       'CREATED', 'UPDATED', 'SUSPENDED', 'REACTIVATED', 'DELETED',
                       'SCOPES_CHANGED', 'SITE_KEY_ROTATED',
                       'CREDENTIAL_CREATED', 'CREDENTIAL_REVOKED', 'TOKEN_DENIED_REPEATEDLY',
                       'DOMAIN_ADDED', 'DOMAIN_REMOVED')),
    detail         VARCHAR(500),                                          -- không bao giờ chứa secret
    actor_id       UUID,                                                  -- NULL = hệ thống
    occurred_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_application_history_app ON application_history (application_id, occurred_at DESC);
CREATE OR REPLACE FUNCTION application_history_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'application_history chỉ cho phép thêm';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_application_history_append_only
    BEFORE UPDATE OR DELETE ON application_history
    FOR EACH ROW EXECUTE FUNCTION application_history_append_only();

-- Domain allowlist: dùng lại bảng origin của ADR-0013. NULL = áp cho mọi Application PUBLIC_SITE của tenant
-- (tương thích ngược với dữ liệu hiện có). FK kép bảo đảm application cùng tenant với origin.
ALTER TABLE tenant_storefront_origins ADD COLUMN application_id UUID;
ALTER TABLE tenant_storefront_origins
    ADD CONSTRAINT fk_storefront_origins_application FOREIGN KEY (application_id, tenant_id)
        REFERENCES applications (id, tenant_id);
CREATE INDEX idx_storefront_origins_application ON tenant_storefront_origins (application_id)
    WHERE application_id IS NOT NULL;
