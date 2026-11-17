-- ADR-0015 B3: khóa quyền liên hệ của đọc giả gửi cửa hàng (6 khóa, sales-service), gán cho mẫu Khách hàng (c4) và Quản lý
-- cửa hàng (c3) và backfill cho tenant đã bật SALES. Cấu trúc theo V20.
-- * Khách hàng (CUSTOMER, c4): chỉ ShopContactController/create.
-- * Quản lý cửa hàng (STORE_MANAGER, c3): 5 khóa SalesContactController. Thu ngân (c1), Thủ kho (c2): không có (tenant admin gán thêm).
-- permissions, role_templates, role_template_permissions là bảng TOÀN CỤC (không RLS); chỉ phần backfill ghi roles/role_permissions
-- (RLS FORCE) nên đặt app.tenant_id từng tenant.

-- ===== 1. Khóa endpoint =====
-- Khóa của khách (ShopContactController) không đi qua lms-api: ghi đường dẫn service /internal/sales/shop/... như V20.
-- Khóa nhân viên theo hợp đồng BFF /api/sales/contact-requests...
INSERT INTO permissions (code, description, source, service, http_method, path_pattern, first_seen_at)
SELECT k.code, k.http_method || ' ' || k.path_pattern || ' (' || k.service || ')', 'AUTO', k.service, k.http_method,
       k.path_pattern, now()
FROM (VALUES
    ('ShopContactController/create',        'sales-service', 'POST', '/internal/sales/shop/contact-requests'),
    ('SalesContactController/search',       'sales-service', 'GET',  '/api/sales/contact-requests'),
    ('SalesContactController/get',          'sales-service', 'GET',  '/api/sales/contact-requests/{id}'),
    ('SalesContactController/getContact',   'sales-service', 'GET',  '/api/sales/contact-requests/{id}/contact'),
    ('SalesContactController/updateStatus', 'sales-service', 'PUT',  '/api/sales/contact-requests/{id}/status'),
    ('SalesContactController/anonymize',    'sales-service', 'POST', '/api/sales/contact-requests/{id}/anonymization')
) AS k (code, service, http_method, path_pattern)
ON CONFLICT (code) DO NOTHING;

-- ===== 2. Backfill tenant ĐÃ bật SALES =====
-- Chạy TRƯỚC khi cập nhật mẫu (mục 3): so khóa hiện có của role với mẫu hiện tại (sau V20) để biết role còn nguyên.
-- Chỉ role CÒN NGUYÊN MẪU mới được thêm khóa mới; role đã chỉnh, không còn tồn tại thì để nguyên (không ghi đè) và ghi
-- WARNING để tenant admin gán tay. Role quản trị (is_admin) bỏ qua kiểm khóa nên không cần.
DO $$
DECLARE
    t        RECORD;
    tpl      RECORD;
    r        RECORD;
    new_keys TEXT[];
    pristine BOOLEAN;
BEGIN
    FOR t IN SELECT tenant_id FROM tenant_modules WHERE module = 'SALES' AND enabled ORDER BY tenant_id LOOP
        PERFORM set_config('app.tenant_id', t.tenant_id::text, true);

        FOR tpl IN SELECT id, name FROM role_templates
                   WHERE id IN ('00000000-0000-0000-0000-0000000000c3', '00000000-0000-0000-0000-0000000000c4')
                   ORDER BY name LOOP
            IF tpl.id = '00000000-0000-0000-0000-0000000000c4' THEN
                new_keys := ARRAY['ShopContactController/create'];
            ELSE
                new_keys := ARRAY[
                    'SalesContactController/search', 'SalesContactController/get', 'SalesContactController/getContact',
                    'SalesContactController/updateStatus', 'SalesContactController/anonymize'];
            END IF;
            SELECT ro.id, ro.is_admin INTO r FROM roles ro WHERE ro.tenant_id = t.tenant_id AND ro.name = tpl.name;
            IF NOT FOUND THEN
                RAISE WARNING 'V22: tenant % không có role % (đã xóa hoặc đổi tên): không gán khóa liên hệ, gán tay nếu cần',
                    t.tenant_id, tpl.name;
                CONTINUE;
            END IF;
            IF r.is_admin THEN
                CONTINUE;
            END IF;
            pristine :=
                NOT EXISTS (SELECT 1 FROM role_permissions rp
                            WHERE rp.role_id = r.id AND rp.permission_code NOT IN (
                                SELECT x.permission_code FROM role_template_permissions x WHERE x.template_id = tpl.id))
                AND NOT EXISTS (SELECT 1 FROM role_template_permissions x
                                WHERE x.template_id = tpl.id AND x.permission_code NOT IN (
                                    SELECT rp.permission_code FROM role_permissions rp WHERE rp.role_id = r.id));
            IF pristine THEN
                INSERT INTO role_permissions (role_id, permission_code)
                SELECT r.id, k FROM unnest(new_keys) AS k
                ON CONFLICT DO NOTHING;
            ELSE
                RAISE WARNING 'V22: tenant % role % đã bị chỉnh so với mẫu: KHÔNG tự gán khóa liên hệ, gán tay các khóa %',
                    t.tenant_id, tpl.name, new_keys;
            END IF;
        END LOOP;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;

-- ===== 3. Cập nhật mẫu Khách hàng và Quản lý cửa hàng (tenant bật SALES từ nay nhận mẫu mới) =====
INSERT INTO role_template_permissions (template_id, permission_code) VALUES
    ('00000000-0000-0000-0000-0000000000c4', 'ShopContactController/create'),
    ('00000000-0000-0000-0000-0000000000c3', 'SalesContactController/search'),
    ('00000000-0000-0000-0000-0000000000c3', 'SalesContactController/get'),
    ('00000000-0000-0000-0000-0000000000c3', 'SalesContactController/getContact'),
    ('00000000-0000-0000-0000-0000000000c3', 'SalesContactController/updateStatus'),
    ('00000000-0000-0000-0000-0000000000c3', 'SalesContactController/anonymize')
ON CONFLICT DO NOTHING;
