-- ADR-0019 mục 2.5: khóa quyền banner cửa hàng và gợi ý tìm kiếm (sales-service), gán cho mẫu Quản lý cửa hàng (c3) và
-- backfill cho tenant đã bật SALES. Cấu trúc theo V22/V25.
-- * Quản lý cửa hàng (STORE_MANAGER, c3): 5 khóa SalesBannerController/*. Thu ngân (c1), Thủ kho (c2), Khách hàng (c4): không có.
-- * StorefrontBannerController/list và StorefrontController/suggestions là endpoint của khách vãng lai (token svc,
--   @StorefrontAccessible): chỉ ĐĂNG KÝ để màn hình phân quyền thấy, KHÔNG gán cho mẫu nào.
-- permissions, role_templates, role_template_permissions là bảng TOÀN CỤC (không RLS); chỉ phần backfill ghi
-- roles/role_permissions (RLS FORCE) nên đặt app.tenant_id từng tenant.

-- ===== 1. Khóa endpoint =====
-- Khóa của khách không đi qua lms-api: ghi đường dẫn service /internal/sales/storefront/... như V20.
-- Khóa nhân viên theo hợp đồng BFF /api/sales/banners...
INSERT INTO permissions (code, description, source, service, http_method, path_pattern, first_seen_at)
SELECT k.code, k.http_method || ' ' || k.path_pattern || ' (' || k.service || ')', 'AUTO', k.service, k.http_method,
       k.path_pattern, now()
FROM (VALUES
    ('SalesBannerController/list',          'sales-service', 'GET',    '/api/sales/banners'),
    ('SalesBannerController/get',           'sales-service', 'GET',    '/api/sales/banners/{id}'),
    ('SalesBannerController/create',        'sales-service', 'POST',   '/api/sales/banners'),
    ('SalesBannerController/update',        'sales-service', 'PUT',    '/api/sales/banners/{id}'),
    ('SalesBannerController/delete',        'sales-service', 'DELETE', '/api/sales/banners/{id}'),
    ('StorefrontBannerController/list',     'sales-service', 'GET',    '/internal/sales/storefront/banners'),
    ('StorefrontController/suggestions',    'sales-service', 'GET',    '/internal/sales/storefront/suggestions')
) AS k (code, service, http_method, path_pattern)
ON CONFLICT (code) DO NOTHING;

-- ===== 2. Backfill tenant ĐÃ bật SALES =====
-- Chạy TRƯỚC khi cập nhật mẫu (mục 3): so khóa hiện có của role với mẫu hiện tại (sau V25) để biết role còn nguyên.
-- Chỉ role CÒN NGUYÊN MẪU mới được thêm khóa; role đã chỉnh, không còn tồn tại thì để nguyên (không ghi đè) và ghi
-- WARNING để tenant admin gán tay. Role quản trị (is_admin) bỏ qua kiểm khóa nên không cần.
DO $$
DECLARE
    t        RECORD;
    tpl      RECORD;
    r        RECORD;
    new_keys TEXT[];
    pristine BOOLEAN;
BEGIN
    new_keys := ARRAY['SalesBannerController/list', 'SalesBannerController/get', 'SalesBannerController/create',
                      'SalesBannerController/update', 'SalesBannerController/delete'];
    FOR t IN SELECT tenant_id FROM tenant_modules WHERE module = 'SALES' AND enabled ORDER BY tenant_id LOOP
        PERFORM set_config('app.tenant_id', t.tenant_id::text, true);

        FOR tpl IN SELECT id, name FROM role_templates
                   WHERE id = '00000000-0000-0000-0000-0000000000c3'
                   ORDER BY name LOOP
            SELECT ro.id, ro.is_admin INTO r FROM roles ro WHERE ro.tenant_id = t.tenant_id AND ro.name = tpl.name;
            IF NOT FOUND THEN
                RAISE WARNING 'V26: tenant % không có role % (đã xóa hoặc đổi tên): không gán khóa banner, gán tay nếu cần',
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
                RAISE WARNING 'V26: tenant % role % đã bị chỉnh so với mẫu: KHÔNG tự gán khóa banner, gán tay các khóa %',
                    t.tenant_id, tpl.name, new_keys;
            END IF;
        END LOOP;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;

-- ===== 3. Cập nhật mẫu Quản lý cửa hàng (tenant bật SALES từ nay nhận mẫu mới) =====
INSERT INTO role_template_permissions (template_id, permission_code) VALUES
    ('00000000-0000-0000-0000-0000000000c3', 'SalesBannerController/list'),
    ('00000000-0000-0000-0000-0000000000c3', 'SalesBannerController/get'),
    ('00000000-0000-0000-0000-0000000000c3', 'SalesBannerController/create'),
    ('00000000-0000-0000-0000-0000000000c3', 'SalesBannerController/update'),
    ('00000000-0000-0000-0000-0000000000c3', 'SalesBannerController/delete')
ON CONFLICT DO NOTHING;
