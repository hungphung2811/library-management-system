-- ADR-0010 B2.3: khóa quyền sales-service pha 2 (21 khóa), cập nhật mẫu Thu ngân/Quản lý cửa hàng, mẫu Khách hàng
-- (signup_default) và backfill cho tenant đã bật SALES. customer-api-data-authorization A19/A5: role Khách hàng chỉ có khóa
-- "của tôi" (ShopController/*) và TitleController/lookup; KHÔNG có StorefrontController/* (đọc công khai dùng token svc).
-- permissions, role_templates, role_template_permissions là bảng TOÀN CỤC (không RLS); chỉ phần backfill ghi roles/role_permissions
-- (RLS FORCE) nên đặt app.tenant_id từng tenant.

-- ===== 1. Khóa endpoint pha 2 =====
-- Đường dẫn nhân viên theo hợp đồng BFF /api/sales/**. Khóa của khách (ShopController) và danh mục công khai
-- (StorefrontController) KHÔNG đi qua lms-api: ghi đường dẫn service /internal/sales/... đúng như code sales-service.
INSERT INTO permissions (code, description, source, service, http_method, path_pattern, first_seen_at)
SELECT k.code, k.http_method || ' ' || k.path_pattern || ' (' || k.service || ')', 'AUTO', k.service, k.http_method,
       k.path_pattern, now()
FROM (VALUES
    ('SalesOrderController/confirm',            'sales-service', 'POST', '/api/sales/orders/{id}/confirmation'),
    ('SalesOrderController/applyDiscount',      'sales-service', 'POST', '/api/sales/orders/{id}/discount'),
    ('SalesOrderController/markPaid',           'sales-service', 'POST', '/api/sales/orders/{id}/payment'),
    ('SalesOrderController/markReadyForPickup', 'sales-service', 'POST', '/api/sales/orders/{id}/ready-for-pickup'),
    ('SalesOrderController/markShipping',       'sales-service', 'POST', '/api/sales/orders/{id}/shipping'),
    ('SalesOrderController/complete',           'sales-service', 'POST', '/api/sales/orders/{id}/completion'),
    ('SalesOrderController/cancel',             'sales-service', 'POST', '/api/sales/orders/{id}/cancellation'),
    ('ShopController/placeOrder',               'sales-service', 'POST', '/internal/sales/shop/orders'),
    ('ShopController/myOrders',                 'sales-service', 'GET',  '/internal/sales/shop/orders'),
    ('ShopController/myOrder',                  'sales-service', 'GET',  '/internal/sales/shop/orders/{id}'),
    ('ShopController/cancelMyOrder',            'sales-service', 'POST', '/internal/sales/shop/orders/{id}/cancellation'),
    ('StorefrontController/list',               'sales-service', 'GET',  '/internal/sales/storefront/items'),
    ('StorefrontController/get',                'sales-service', 'GET',  '/internal/sales/storefront/items/{titleId}'),
    ('StorefrontController/preview',            'sales-service', 'GET',  '/internal/sales/storefront/items/{titleId}/preview'),
    ('SalesReturnController/create',            'sales-service', 'POST', '/api/sales/returns'),
    ('SalesReturnController/createLate',        'sales-service', 'POST', '/api/sales/returns/late'),
    ('SalesReturnController/get',               'sales-service', 'GET',  '/api/sales/returns/{id}'),
    ('SalesReturnController/listByOrder',       'sales-service', 'GET',  '/api/sales/orders/{id}/returns'),
    ('RefundController/search',                 'sales-service', 'GET',  '/api/sales/refunds'),
    ('RefundController/markRefunded',           'sales-service', 'POST', '/api/sales/refunds/{id}/completion'),
    ('SalesAuditController/search',             'sales-service', 'GET',  '/api/sales/audit-log')
) AS k (code, service, http_method, path_pattern)
ON CONFLICT (code) DO NOTHING;

-- ===== 2. Mẫu role Khách hàng (module SALES, signup_default) =====
-- Chỉ khóa "của tôi" + TitleController/lookup (sales gọi catalog bằng token khách khi đặt hàng). Không khóa nhân viên,
-- không StorefrontController/*. Tên role là dữ liệu, tenant admin đổi được.
INSERT INTO role_templates (id, name, description, is_admin, module, signup_default) VALUES
    ('00000000-0000-0000-0000-0000000000c4', 'CUSTOMER',
     'Khách hàng: tự đăng ký, đặt và xem đơn của chính mình (không có quyền nhân viên)', FALSE, 'SALES', TRUE)
ON CONFLICT DO NOTHING;

INSERT INTO role_template_permissions (template_id, permission_code) VALUES
    ('00000000-0000-0000-0000-0000000000c4', 'ShopController/placeOrder'),
    ('00000000-0000-0000-0000-0000000000c4', 'ShopController/myOrders'),
    ('00000000-0000-0000-0000-0000000000c4', 'ShopController/myOrder'),
    ('00000000-0000-0000-0000-0000000000c4', 'ShopController/cancelMyOrder'),
    ('00000000-0000-0000-0000-0000000000c4', 'TitleController/lookup')
ON CONFLICT DO NOTHING;

-- ===== 3. Backfill tenant ĐÃ bật SALES =====
-- Chạy TRƯỚC khi cập nhật mẫu CASHIER/STORE_MANAGER (mục 4): so khóa hiện có của role với mẫu V17 để biết role còn nguyên.
-- * CASHIER, STORE_MANAGER: chỉ khi tập khóa của role TRÙNG ĐÚNG mẫu V17 (chưa bị tenant chỉnh) mới thêm khóa mới; role đã
--   chỉnh hoặc không còn tồn tại thì để nguyên (giống V17: không ghi đè) và ghi WARNING để gán tay.
--   STOCK_KEEPER không có khóa mới. Role quản trị (is_admin) bỏ qua kiểm khóa nên không cần.
-- * CUSTOMER: tạo role nếu tenant chưa có role cùng tên. Cờ signup_default chỉ bật khi tenant CHƯA có role signup_default
--   nào (chỉ mục duy nhất từng phần); trước V20 chưa tenant nào có, nên mọi tenant đang bật SALES sẽ mở tự đăng ký.
DO $$
DECLARE
    t        RECORD;
    tpl      RECORD;
    r        RECORD;
    new_keys TEXT[];
    pristine BOOLEAN;
    new_id   UUID;
    inserted INTEGER;
    signup   BOOLEAN;
BEGIN
    FOR t IN SELECT tenant_id FROM tenant_modules WHERE module = 'SALES' AND enabled ORDER BY tenant_id LOOP
        PERFORM set_config('app.tenant_id', t.tenant_id::text, true);

        FOR tpl IN SELECT id, name FROM role_templates
                   WHERE id IN ('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-0000000000c3')
                   ORDER BY name LOOP
            IF tpl.id = '00000000-0000-0000-0000-0000000000c1' THEN
                new_keys := ARRAY[
                    'SalesReturnController/create', 'SalesReturnController/get', 'SalesReturnController/listByOrder',
                    'SalesOrderController/confirm', 'SalesOrderController/markPaid',
                    'SalesOrderController/markReadyForPickup', 'SalesOrderController/markShipping',
                    'SalesOrderController/complete', 'SalesOrderController/cancel'];
            ELSE
                new_keys := ARRAY[
                    'SalesOrderController/confirm', 'SalesOrderController/applyDiscount',
                    'SalesOrderController/markPaid', 'SalesOrderController/markReadyForPickup',
                    'SalesOrderController/markShipping', 'SalesOrderController/complete',
                    'SalesOrderController/cancel', 'StorefrontController/list', 'StorefrontController/get',
                    'StorefrontController/preview', 'SalesReturnController/create',
                    'SalesReturnController/createLate', 'SalesReturnController/get',
                    'SalesReturnController/listByOrder', 'RefundController/search', 'RefundController/markRefunded',
                    'SalesAuditController/search'];
            END IF;
            SELECT ro.id, ro.is_admin INTO r FROM roles ro WHERE ro.tenant_id = t.tenant_id AND ro.name = tpl.name;
            IF NOT FOUND THEN
                RAISE WARNING 'V20: tenant % không có role % (đã xóa hoặc đổi tên): không gán khóa mới, gán tay nếu cần',
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
                RAISE WARNING 'V20: tenant % role % đã bị chỉnh so với mẫu: KHÔNG tự gán khóa pha 2, gán tay các khóa %',
                    t.tenant_id, tpl.name, new_keys;
            END IF;
        END LOOP;

        -- Khách hàng
        SELECT rt.name, rt.description INTO tpl FROM role_templates rt WHERE rt.id = '00000000-0000-0000-0000-0000000000c4';
        signup := NOT EXISTS (SELECT 1 FROM roles ro WHERE ro.tenant_id = t.tenant_id AND ro.signup_default);
        new_id := gen_random_uuid();
        INSERT INTO roles (id, name, description, is_admin, tenant_id, signup_default)
        VALUES (new_id, tpl.name, tpl.description, FALSE, t.tenant_id, signup)
        ON CONFLICT (tenant_id, name) DO NOTHING;
        GET DIAGNOSTICS inserted = ROW_COUNT;
        IF inserted > 0 THEN
            INSERT INTO role_permissions (role_id, permission_code)
            SELECT new_id, rtp.permission_code FROM role_template_permissions rtp
            WHERE rtp.template_id = '00000000-0000-0000-0000-0000000000c4';
            IF NOT signup THEN
                RAISE WARNING 'V20: tenant % đã có role signup_default khác: CUSTOMER được tạo KHÔNG mang cờ (không tự đổi role đăng ký)',
                    t.tenant_id;
            END IF;
        ELSE
            RAISE WARNING 'V20: tenant % đã có role tên CUSTOMER: không ghi đè, kiểm tra khóa và cờ signup_default bằng tay', t.tenant_id;
        END IF;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;

-- ===== 4. Cập nhật mẫu Thu ngân và Quản lý cửa hàng (tenant tạo/bật SALES từ nay nhận mẫu mới) =====
-- Thu ngân: xử lý trả/đổi trong hạn (create, get, listByOrder) và thao tác đơn online tại quầy; KHÔNG applyDiscount,
-- KHÔNG createLate (trả quá hạn), KHÔNG hoàn tiền, KHÔNG nhật ký. Thủ kho: không đổi (không đơn).
INSERT INTO role_template_permissions (template_id, permission_code) VALUES
    ('00000000-0000-0000-0000-0000000000c1', 'SalesReturnController/create'),
    ('00000000-0000-0000-0000-0000000000c1', 'SalesReturnController/get'),
    ('00000000-0000-0000-0000-0000000000c1', 'SalesReturnController/listByOrder'),
    ('00000000-0000-0000-0000-0000000000c1', 'SalesOrderController/confirm'),
    ('00000000-0000-0000-0000-0000000000c1', 'SalesOrderController/markPaid'),
    ('00000000-0000-0000-0000-0000000000c1', 'SalesOrderController/markReadyForPickup'),
    ('00000000-0000-0000-0000-0000000000c1', 'SalesOrderController/markShipping'),
    ('00000000-0000-0000-0000-0000000000c1', 'SalesOrderController/complete'),
    ('00000000-0000-0000-0000-0000000000c1', 'SalesOrderController/cancel')
ON CONFLICT DO NOTHING;

-- Quản lý cửa hàng: mọi khóa nhân viên pha 2, gồm createLate (CHỈ role này), applyDiscount, hoàn tiền, nhật ký và danh mục
-- công khai. KHÔNG có ShopController/* (khóa "của tôi" của khách, vô nghĩa với nhân viên, nguyên tắc tối thiểu).
INSERT INTO role_template_permissions (template_id, permission_code) VALUES
    ('00000000-0000-0000-0000-0000000000c3', 'SalesOrderController/confirm'),
    ('00000000-0000-0000-0000-0000000000c3', 'SalesOrderController/applyDiscount'),
    ('00000000-0000-0000-0000-0000000000c3', 'SalesOrderController/markPaid'),
    ('00000000-0000-0000-0000-0000000000c3', 'SalesOrderController/markReadyForPickup'),
    ('00000000-0000-0000-0000-0000000000c3', 'SalesOrderController/markShipping'),
    ('00000000-0000-0000-0000-0000000000c3', 'SalesOrderController/complete'),
    ('00000000-0000-0000-0000-0000000000c3', 'SalesOrderController/cancel'),
    ('00000000-0000-0000-0000-0000000000c3', 'StorefrontController/list'),
    ('00000000-0000-0000-0000-0000000000c3', 'StorefrontController/get'),
    ('00000000-0000-0000-0000-0000000000c3', 'StorefrontController/preview'),
    ('00000000-0000-0000-0000-0000000000c3', 'SalesReturnController/create'),
    ('00000000-0000-0000-0000-0000000000c3', 'SalesReturnController/createLate'),
    ('00000000-0000-0000-0000-0000000000c3', 'SalesReturnController/get'),
    ('00000000-0000-0000-0000-0000000000c3', 'SalesReturnController/listByOrder'),
    ('00000000-0000-0000-0000-0000000000c3', 'RefundController/search'),
    ('00000000-0000-0000-0000-0000000000c3', 'RefundController/markRefunded'),
    ('00000000-0000-0000-0000-0000000000c3', 'SalesAuditController/search')
ON CONFLICT DO NOTHING;
