-- Menu điều hướng cho FE (ADR-0007): cây cha-con theo tenant, mỗi mục gắn tùy chọn một khóa quyền Controller/method.
-- Thứ tự: tạo bảng + ràng buộc + dữ liệu khởi tạo TRƯỚC, bật RLS SAU CÙNG (FORCE RLS áp cả chủ bảng, tức chính vai
-- trò chạy migration này; cùng mẫu circulation V6). system_db không có bảng tenants nên tenant_id không có FK.
CREATE TABLE menus (
    id             UUID         PRIMARY KEY,
    tenant_id      UUID         NOT NULL,
    parent_id      UUID         NULL,
    code           VARCHAR(100) NOT NULL,
    label          VARCHAR(200) NOT NULL,
    path           VARCHAR(500) NULL,
    icon           VARCHAR(100) NULL,
    sort_order     INT          NOT NULL DEFAULT 0,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    permission_key VARCHAR(255) NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_menus_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT uq_menus_id_tenant UNIQUE (id, tenant_id),
    -- Khóa ngoại kép: cha luôn cùng tenant với con; không xóa dây chuyền ngầm (MENU_HAS_CHILDREN ở tầng nghiệp vụ).
    -- parent_id NULL (mục gốc) thì ràng buộc không áp (MATCH SIMPLE).
    CONSTRAINT fk_menus_parent_tenant FOREIGN KEY (parent_id, tenant_id)
        REFERENCES menus (id, tenant_id) ON DELETE RESTRICT
);
CREATE INDEX idx_menus_tenant_parent_sort ON menus (tenant_id, parent_id, sort_order);

-- ===== Dữ liệu khởi tạo cho tenant mặc định (id do identity V11 tạo), tương ứng DEFAULT_NAV_LINKS của FE =====
INSERT INTO menus (id, tenant_id, parent_id, code, label, path, sort_order, permission_key) VALUES
    ('00000000-0000-0000-0000-00000000e001', '00000000-0000-0000-0000-0000000000d1', NULL, 'home',
     'Trang chủ', '/', 10, NULL),
    ('00000000-0000-0000-0000-00000000e002', '00000000-0000-0000-0000-0000000000d1', NULL, 'catalog',
     'Sách', '/catalog', 20, NULL),
    ('00000000-0000-0000-0000-00000000e003', '00000000-0000-0000-0000-0000000000d1', NULL, 'circulation',
     'Mượn trả', '/circulation', 30, NULL),
    ('00000000-0000-0000-0000-00000000e004', '00000000-0000-0000-0000-0000000000d1', NULL, 'admin',
     'Quản trị', '/admin', 40, 'AccessControlController/listRoles');

-- ===== Row-Level Security (fail-closed: không có app.tenant_id thì không thấy và không ghi được dòng nào) =====
-- Mẫu chung của tenant-common (TenantRlsConnectionInitializer). FORCE để cả chủ bảng cũng chịu RLS.
ALTER TABLE menus ENABLE ROW LEVEL SECURITY;
ALTER TABLE menus FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON menus
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
