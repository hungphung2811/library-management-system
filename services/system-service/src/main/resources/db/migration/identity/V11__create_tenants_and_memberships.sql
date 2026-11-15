-- Mô hình đa tenant (bước 1): tổ chức, tenant, membership, mẫu role. Chỉ thêm bảng/cột mới; các bảng hiện có
-- (roles, departments, reader_profiles, refresh_tokens...) chưa gắn tenant ở script này.
CREATE TABLE organizations (
    id         UUID                     PRIMARY KEY,
    name       VARCHAR(255)             NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE TABLE tenants (
    id              UUID                     PRIMARY KEY,
    code            VARCHAR(50)              NOT NULL UNIQUE,
    name            VARCHAR(255)             NOT NULL,
    organization_id UUID                     REFERENCES organizations (id),
    status          VARCHAR(20)              NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Bảng toàn cục (không RLS): cần đọc được trước khi biết tenant hiện tại (đăng nhập, chọn tenant).
CREATE TABLE tenant_memberships (
    user_id       UUID                     NOT NULL,
    tenant_id     UUID                     NOT NULL REFERENCES tenants (id),
    department_id UUID,
    status        VARCHAR(20)              NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    joined_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, tenant_id)
);
CREATE INDEX idx_tenant_memberships_tenant ON tenant_memberships (tenant_id);

CREATE TABLE role_templates (
    id          UUID         PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(255) NOT NULL,
    is_admin    BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE TABLE role_template_permissions (
    template_id     UUID         NOT NULL REFERENCES role_templates (id) ON DELETE CASCADE,
    permission_code VARCHAR(200) NOT NULL,
    PRIMARY KEY (template_id, permission_code)
);

ALTER TABLE credentials ADD COLUMN platform_admin BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE credentials ADD COLUMN last_tenant_id UUID;

-- Tenant mặc định cho dữ liệu hiện có
INSERT INTO tenants (id, code, name, organization_id, status)
VALUES ('00000000-0000-0000-0000-0000000000d1', 'default', 'Thư viện mặc định', NULL, 'ACTIVE');

-- Mọi người dùng hiện có (bảng users và cả tài khoản chỉ có credential) là thành viên ACTIVE của tenant mặc định;
-- phòng ban lấy từ users.department_id (cột được giữ nguyên), nếu không có thì từ credentials.department_id.
INSERT INTO tenant_memberships (user_id, tenant_id, department_id, status)
SELECT ids.user_id, '00000000-0000-0000-0000-0000000000d1', COALESCE(u.department_id, c.department_id), 'ACTIVE'
FROM (SELECT user_id FROM credentials UNION SELECT id FROM users) ids
LEFT JOIN users u ON u.id = ids.user_id
LEFT JOIN credentials c ON c.user_id = ids.user_id;

-- Mẫu role khởi tạo từ 5 role seed (id a1..a5) theo quyền hiệu lực sau V9; cờ is_admin lấy từ dữ liệu roles.
INSERT INTO role_templates (id, name, description, is_admin)
SELECT id, name, description, is_admin FROM roles
WHERE id IN ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-0000000000a2',
             '00000000-0000-0000-0000-0000000000a3', '00000000-0000-0000-0000-0000000000a4',
             '00000000-0000-0000-0000-0000000000a5');

INSERT INTO role_template_permissions (template_id, permission_code)
SELECT rp.role_id, rp.permission_code FROM role_permissions rp
WHERE rp.role_id IN (SELECT id FROM role_templates);
