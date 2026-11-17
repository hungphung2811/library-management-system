-- ADR-0010 B2.1 (tự đăng ký), customer-api-data-authorization A1/A18 (kênh token) và DA-3 (khóa getAny).
-- roles/role_permissions có RLS FORCE: phần backfill role đặt app.tenant_id từng tenant (cục bộ transaction).
-- permissions, role_templates, role_template_permissions, refresh_tokens là bảng toàn cục (không RLS).

-- ===== 1. refresh_tokens.channel: refresh giữ kênh của token gốc =====
-- STAFF: đăng nhập qua lms-api (mọi token phát hành trước V18 đều là STAFF). STOREFRONT: đăng nhập qua customer-api.
ALTER TABLE refresh_tokens
    ADD COLUMN channel VARCHAR(20) NOT NULL DEFAULT 'STAFF' CHECK (channel IN ('STAFF', 'STOREFRONT'));

-- ===== 2. Cờ signup_default =====
-- Role mà tài khoản tự đăng ký nhận. Role quản trị (is_admin) KHÔNG BAO GIỜ mang cờ (CHECK ở DB, thêm luật ở domain);
-- mỗi tenant có tối đa một role signup_default (chỉ mục duy nhất từng phần). Cờ mặc định FALSE: tenant hiện có không
-- tự mở đăng ký; mẫu Khách hàng (module SALES, signup_default = TRUE) do ADR-0010 B2.3 seed.
ALTER TABLE roles
    ADD COLUMN signup_default BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT ck_roles_signup_not_admin CHECK (NOT (signup_default AND is_admin));
CREATE UNIQUE INDEX uq_roles_signup_default_per_tenant ON roles (tenant_id) WHERE signup_default;

ALTER TABLE role_templates
    ADD COLUMN signup_default BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT ck_role_templates_signup_not_admin CHECK (NOT (signup_default AND is_admin));
-- Tối đa một mẫu signup_default cho mỗi module (tenant bật nhiều module vẫn chỉ nhận một role signup_default hợp lệ
-- vì seed bỏ qua tên role đã có; chỉ mục roles chặn phần còn lại).
CREATE UNIQUE INDEX uq_role_templates_signup_default_per_module ON role_templates (COALESCE(module, '')) WHERE signup_default;

-- ===== 3. Khóa BorrowingController/getAny (DA-3) =====
-- GET /internal/borrowings/{id}/detail (BFF: /api/borrowings/{id}/detail): nhân viên xem phiếu mượn bất kỳ trong tenant.
-- BorrowingController/get chỉ còn trả phiếu của chính người gọi nên thủ thư mất khả năng xem phiếu người khác nếu
-- không có khóa này. role_permissions có FK tới permissions(code): chèn khóa trước khi gán.
INSERT INTO permissions (code, description, source, service, http_method, path_pattern, first_seen_at)
VALUES ('BorrowingController/getAny', 'GET /api/borrowings/{id}/detail (circulation-service)', 'AUTO',
        'circulation-service', 'GET', '/api/borrowings/{id}/detail', now())
ON CONFLICT (code) DO NOTHING;

-- Mẫu role: SUPER_ADMIN (a1, quản trị: khóa chỉ để đồng bộ danh sách), LIBRARY_ADMIN (a2, quản trị thư viện) và
-- LIBRARIAN (a3, thủ thư). KHÔNG gán cho DEPARTMENT_MANAGER (a4) và MEMBER (a5): không xử lý phiếu của người khác (MEMBER
-- xem phiếu của chính mình bằng BorrowingController/get). Chỉ tenant TẠO MỚI sau V18 nhận mẫu mới.
INSERT INTO role_template_permissions (template_id, permission_code)
SELECT t.id, 'BorrowingController/getAny'
FROM role_templates t
WHERE t.id IN ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-0000000000a2',
               '00000000-0000-0000-0000-0000000000a3')
ON CONFLICT DO NOTHING;

-- ===== 4. Backfill role của tenant hiện có (chỉ khôi phục năng lực cũ) =====
-- Trước thay đổi này mọi người đăng nhập xem được phiếu bất kỳ (BorrowingController/get chỉ @AuthenticatedOnly).
-- Backfill an toàn: chỉ role KHÔNG phải admin đang giữ BorrowingController/returnBook (dấu hiệu role nhân viên xử lý
-- phiếu, bất kể tên role do tenant đặt) mới nhận getAny; role admin bỏ qua kiểm khóa nên không cần. Không đụng role
-- không giữ returnBook (thành viên, quản lý phòng ban...). Tenant đã chủ ý bỏ returnBook khỏi một role thủ thư sẽ không nhận
-- getAny và phải gán tay.
DO $$
DECLARE
    t RECORD;
BEGIN
    FOR t IN SELECT id FROM tenants ORDER BY id LOOP
        PERFORM set_config('app.tenant_id', t.id::text, true);
        INSERT INTO role_permissions (role_id, permission_code)
        SELECT rp.role_id, 'BorrowingController/getAny'
        FROM role_permissions rp
        JOIN roles r ON r.id = rp.role_id
        WHERE rp.permission_code = 'BorrowingController/returnBook' AND NOT r.is_admin AND r.tenant_id = t.id
        ON CONFLICT DO NOTHING;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;
