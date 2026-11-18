-- ADR-0021: khóa quyền của lệnh thanh toán POS hai bước (sales-service, PaymentIntentController), gán cho mẫu Thu ngân (c1)
-- và Quản lý cửa hàng (c3), và backfill cho tenant đã bật SALES. Cấu trúc theo V26.
-- * Thu ngân (c1) và Quản lý cửa hàng (c3): get, cancel, confirm. Không gán simulatePaid (nút giả lập chỉ cho dev/demo, ghi vào
--   tay nếu cần); khóa vẫn được ĐĂNG KÝ để màn hình phân quyền thấy.
-- * Tên file cố ý không bắt đầu bằng "sales_": SalesPermissionSeedMatchesCodeTest chỉ đối chiếu các file V*__sales_*.sql với
--   controller dưới /internal/sales/**, còn controller này nằm ở /internal/payments/**.
-- permissions, role_templates, role_template_permissions là bảng TOÀN CỤC (không RLS); chỉ phần backfill ghi roles/role_permissions
-- (RLS FORCE) nên đặt app.tenant_id từng tenant.

-- ===== 1. Khóa endpoint (đường dẫn theo hợp đồng BFF /api/sales/payments/intents...) =====
INSERT INTO permissions (code, description, source, service, http_method, path_pattern, first_seen_at)
SELECT k.code, k.http_method || ' ' || k.path_pattern || ' (' || k.service || ')', 'AUTO', k.service, k.http_method,
       k.path_pattern, now()
FROM (VALUES
    ('PaymentIntentController/get',          'sales-service', 'GET',  '/api/sales/payments/intents/{id}'),
    ('PaymentIntentController/cancel',       'sales-service', 'POST', '/api/sales/payments/intents/{id}/cancel'),
    ('PaymentIntentController/confirm',      'sales-service', 'POST', '/api/sales/payments/intents/{id}/confirm'),
    ('PaymentIntentController/simulatePaid', 'sales-service', 'POST', '/api/sales/payments/intents/{id}/simulate-paid')
) AS k (code, service, http_method, path_pattern)
ON CONFLICT (code) DO NOTHING;

-- ===== 2. Backfill tenant ĐÃ bật SALES (trước khi cập nhật mẫu, như V26) =====
-- Chỉ role CÒN NGUYÊN MẪU mới được thêm khóa; role đã chỉnh hoặc không còn thì để nguyên và ghi WARNING để tenant admin gán tay.
DO $$
DECLARE
    t        RECORD;
    tpl      RECORD;
    r        RECORD;
    new_keys TEXT[];
    pristine BOOLEAN;
BEGIN
    new_keys := ARRAY['PaymentIntentController/get', 'PaymentIntentController/cancel', 'PaymentIntentController/confirm'];
    FOR t IN SELECT tenant_id FROM tenant_modules WHERE module = 'SALES' AND enabled ORDER BY tenant_id LOOP
        PERFORM set_config('app.tenant_id', t.tenant_id::text, true);

        FOR tpl IN SELECT id, name FROM role_templates
                   WHERE id IN ('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-0000000000c3')
                   ORDER BY name LOOP
            SELECT ro.id, ro.is_admin INTO r FROM roles ro WHERE ro.tenant_id = t.tenant_id AND ro.name = tpl.name;
            IF NOT FOUND THEN
                RAISE WARNING 'V29: tenant % không có role % (đã xóa hoặc đổi tên): không gán khóa thanh toán, gán tay nếu cần',
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
                RAISE WARNING 'V29: tenant % role % đã bị chỉnh so với mẫu: KHÔNG tự gán khóa thanh toán, gán tay các khóa %',
                    t.tenant_id, tpl.name, new_keys;
            END IF;
        END LOOP;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;

-- ===== 3. Cập nhật mẫu (tenant bật SALES từ nay nhận mẫu mới) =====
INSERT INTO role_template_permissions (template_id, permission_code) VALUES
    ('00000000-0000-0000-0000-0000000000c1', 'PaymentIntentController/get'),
    ('00000000-0000-0000-0000-0000000000c1', 'PaymentIntentController/cancel'),
    ('00000000-0000-0000-0000-0000000000c1', 'PaymentIntentController/confirm'),
    ('00000000-0000-0000-0000-0000000000c3', 'PaymentIntentController/get'),
    ('00000000-0000-0000-0000-0000000000c3', 'PaymentIntentController/cancel'),
    ('00000000-0000-0000-0000-0000000000c3', 'PaymentIntentController/confirm')
ON CONFLICT DO NOTHING;
