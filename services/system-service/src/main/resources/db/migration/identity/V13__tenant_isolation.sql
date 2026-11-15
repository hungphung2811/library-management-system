-- Cách ly dữ liệu identity theo tenant (bước 2): role, gán role, phòng ban, hồ sơ độc giả thuộc tenant.
-- Dữ liệu hiện có về tenant mặc định (do V11 tạo). Bảng toàn cục giữ nguyên, không RLS: credentials, users,
-- external_logins, permissions, role_templates(+permissions), tenants, organizations, tenant_memberships và
-- refresh_tokens (hai bảng sau có cột tenant_id thường, đọc trước khi biết tenant: lọc ở tầng ứng dụng).

-- ===== Cột tenant_id + backfill tenant mặc định =====
ALTER TABLE roles ADD COLUMN tenant_id UUID;
UPDATE roles SET tenant_id = '00000000-0000-0000-0000-0000000000d1';
ALTER TABLE roles ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE roles ADD CONSTRAINT fk_roles_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id);

ALTER TABLE departments ADD COLUMN tenant_id UUID;
UPDATE departments SET tenant_id = '00000000-0000-0000-0000-0000000000d1';
ALTER TABLE departments ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE departments ADD CONSTRAINT fk_departments_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id);

ALTER TABLE reader_profiles ADD COLUMN tenant_id UUID;
UPDATE reader_profiles SET tenant_id = '00000000-0000-0000-0000-0000000000d1';
ALTER TABLE reader_profiles ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE reader_profiles ADD CONSTRAINT fk_reader_profiles_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id);

-- user_roles mang tenant của role (cột riêng để RLS lọc trực tiếp); khóa ngoại kép bảo đảm luôn trùng tenant của role.
ALTER TABLE user_roles ADD COLUMN tenant_id UUID;
UPDATE user_roles ur SET tenant_id = r.tenant_id FROM roles r WHERE r.id = ur.role_id;
ALTER TABLE user_roles ALTER COLUMN tenant_id SET NOT NULL;

-- ===== Ràng buộc unique theo tenant =====
ALTER TABLE roles DROP CONSTRAINT roles_name_key;
ALTER TABLE roles ADD CONSTRAINT uq_roles_tenant_name UNIQUE (tenant_id, name);
ALTER TABLE roles ADD CONSTRAINT uq_roles_id_tenant UNIQUE (id, tenant_id);

ALTER TABLE user_roles DROP CONSTRAINT user_roles_role_id_fkey;
ALTER TABLE user_roles ADD CONSTRAINT fk_user_roles_role_tenant
    FOREIGN KEY (role_id, tenant_id) REFERENCES roles (id, tenant_id);
CREATE INDEX idx_user_roles_tenant_user ON user_roles (tenant_id, user_id);

ALTER TABLE departments DROP CONSTRAINT departments_code_key;
ALTER TABLE departments ADD CONSTRAINT uq_departments_tenant_code UNIQUE (tenant_id, code);
ALTER TABLE departments ADD CONSTRAINT uq_departments_id_tenant UNIQUE (id, tenant_id);
-- Phòng ban cha phải cùng tenant
ALTER TABLE departments DROP CONSTRAINT departments_parent_id_fkey;
ALTER TABLE departments ADD CONSTRAINT fk_departments_parent_tenant
    FOREIGN KEY (parent_id, tenant_id) REFERENCES departments (id, tenant_id);

ALTER TABLE reader_profiles DROP CONSTRAINT reader_profiles_pkey;
ALTER TABLE reader_profiles ADD PRIMARY KEY (tenant_id, user_id);
ALTER TABLE reader_profiles DROP CONSTRAINT reader_profiles_card_no_key;
ALTER TABLE reader_profiles ADD CONSTRAINT uq_reader_profiles_tenant_card_no UNIQUE (tenant_id, card_no);

-- ===== Row-Level Security (fail-closed: không có app.tenant_id thì không thấy và không ghi được dòng nào) =====
-- Mẫu chung của tenant-common (TenantRlsConnectionInitializer). FORCE để cả chủ bảng cũng chịu RLS.
ALTER TABLE roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE roles FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON roles
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE user_roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_roles FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON user_roles
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE departments ENABLE ROW LEVEL SECURITY;
ALTER TABLE departments FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON departments
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE reader_profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE reader_profiles FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON reader_profiles
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- role_permissions không có cột tenant: chỉ thấy/ghi được quyền của role mà tenant hiện tại thấy (bảng roles đã RLS).
ALTER TABLE role_permissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE role_permissions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON role_permissions
    USING (EXISTS (SELECT 1 FROM roles r WHERE r.id = role_permissions.role_id))
    WITH CHECK (EXISTS (SELECT 1 FROM roles r WHERE r.id = role_permissions.role_id));
