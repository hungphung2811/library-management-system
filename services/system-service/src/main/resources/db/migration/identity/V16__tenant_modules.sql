-- ADR-0009: module theo tenant (LENDING, SALES). tenant_modules là bảng TOÀN CỤC như tenants (không RLS, không tenant_id
-- kiểu @TenantId): platform admin không có tenant vẫn đọc/ghi được và TokenService đọc trước khi vào ngữ cảnh tenant.
-- Giữ dòng khi tắt (enabled = false) để biết lần đổi cuối; lịch sử đầy đủ nằm ở audit.
CREATE TABLE tenant_modules (
    tenant_id  UUID                     NOT NULL REFERENCES tenants (id),
    module     VARCHAR(20)              NOT NULL CHECK (module IN ('LENDING', 'SALES')),
    enabled    BOOLEAN                  NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_by UUID,
    PRIMARY KEY (tenant_id, module)
);

-- Backfill: mọi tenant hiện có giữ hành vi cũ (LENDING). Bảng không RLS nên không cần set_config.
INSERT INTO tenant_modules (tenant_id, module, enabled)
SELECT id, 'LENDING', TRUE FROM tenants;

-- Mẫu role theo module: NULL = lõi (luôn chép khi tạo tenant), giá trị = chỉ chép khi tenant có module đó.
-- a2..a5 (LIBRARIAN, MEMBER, READER, AUDITOR) thuộc LENDING; a1 (SUPER_ADMIN, quản trị) giữ NULL.
ALTER TABLE role_templates
    ADD COLUMN module VARCHAR(20) CHECK (module IN ('LENDING', 'SALES'));

UPDATE role_templates SET module = 'LENDING'
WHERE id IN ('00000000-0000-0000-0000-0000000000a2', '00000000-0000-0000-0000-0000000000a3',
             '00000000-0000-0000-0000-0000000000a4', '00000000-0000-0000-0000-0000000000a5');
