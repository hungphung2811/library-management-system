-- B1.6 (ADR-0010 mục 2.5/2.6, sales-module-design mục 5/7): khóa quyền của sales-service pha 1 và mẫu role module SALES.
-- permissions, role_templates, role_template_permissions là bảng TOÀN CỤC (không RLS) nên phần seed không cần set_config.
-- Chỉ phần backfill role cho tenant đã bật SALES ghi vào roles/role_permissions (có RLS FORCE) nên đặt app.tenant_id.

-- ===== 1. Khóa endpoint (Controller/method) =====
-- role_permissions.permission_code có FK tới permissions(code): phải chèn khóa trước khi gán. Đường dẫn theo hợp đồng
-- BFF /api/** (như V9); khóa thật do sales-service tự đăng ký lại lần đầu endpoint được gọi (ON CONFLICT DO NOTHING).
INSERT INTO permissions (code, description, source, service, http_method, path_pattern, first_seen_at)
SELECT k.code, k.http_method || ' ' || k.path_pattern || ' (' || k.service || ')', 'AUTO', k.service, k.http_method,
       k.path_pattern, now()
FROM (VALUES
    ('SalesSettingsController/get',            'sales-service',   'GET',  '/api/sales/settings'),
    ('SalesSettingsController/update',         'sales-service',   'PUT',  '/api/sales/settings'),
    ('StockItemController/register',           'sales-service',   'POST', '/api/sales/stock-items'),
    ('StockItemController/search',             'sales-service',   'GET',  '/api/sales/stock-items'),
    ('StockItemController/get',                'sales-service',   'GET',  '/api/sales/stock-items/{titleId}'),
    ('StockItemController/updatePrice',        'sales-service',   'PUT',  '/api/sales/stock-items/{titleId}/price'),
    ('StockItemController/ledger',             'sales-service',   'GET',  '/api/sales/stock-items/{titleId}/ledger'),
    ('StockAdjustmentController/adjust',       'sales-service',   'POST', '/api/sales/stock-adjustments'),
    ('StockAdjustmentController/stocktake',    'sales-service',   'POST', '/api/sales/stocktakes'),
    ('SupplierController/search',              'sales-service',   'GET',  '/api/sales/suppliers'),
    ('SupplierController/create',              'sales-service',   'POST', '/api/sales/suppliers'),
    ('SupplierController/update',              'sales-service',   'PUT',  '/api/sales/suppliers/{id}'),
    ('GoodsReceiptController/create',          'sales-service',   'POST', '/api/sales/goods-receipts'),
    ('GoodsReceiptController/search',          'sales-service',   'GET',  '/api/sales/goods-receipts'),
    ('GoodsReceiptController/get',             'sales-service',   'GET',  '/api/sales/goods-receipts/{id}'),
    ('GoodsReceiptController/confirm',         'sales-service',   'POST', '/api/sales/goods-receipts/{id}/confirmation'),
    ('GoodsReceiptController/cancel',          'sales-service',   'POST', '/api/sales/goods-receipts/{id}/cancellation'),
    ('PosOrderController/create',              'sales-service',   'POST', '/api/sales/pos-orders'),
    ('PosOrderController/createWithDiscount',  'sales-service',   'POST', '/api/sales/pos-orders/discounted'),
    ('SalesOrderController/search',            'sales-service',   'GET',  '/api/sales/orders'),
    ('SalesOrderController/get',               'sales-service',   'GET',  '/api/sales/orders/{id}'),
    ('SalesOrderController/getContact',        'sales-service',   'GET',  '/api/sales/orders/{id}/contact'),
    ('SalesOrderController/receipt',           'sales-service',   'GET',  '/api/sales/orders/{id}/receipt'),
    ('TitleController/lookup',                 'catalog-service', 'POST', '/api/books/lookup')
) AS k (code, service, http_method, path_pattern)
ON CONFLICT (code) DO NOTHING;

-- ===== 2. Mẫu role module SALES =====
-- Tên role là dữ liệu, tenant admin đổi được. Khách hàng (signup_default) thuộc pha 2, không có ở đây.
INSERT INTO role_templates (id, name, description, is_admin, module) VALUES
    ('00000000-0000-0000-0000-0000000000c1', 'CASHIER',
     'Thu ngân: bán hàng tại quầy (không giảm giá), xem đơn và in phiếu, tra tồn', FALSE, 'SALES'),
    ('00000000-0000-0000-0000-0000000000c2', 'STOCK_KEEPER',
     'Thủ kho: mặt hàng, tồn kho, kiểm kê, nhà cung cấp, phiếu nhập', FALSE, 'SALES'),
    ('00000000-0000-0000-0000-0000000000c3', 'STORE_MANAGER',
     'Quản lý cửa hàng: toàn bộ nghiệp vụ bán hàng, giảm giá, cấu hình, xem liên hệ khách', FALSE, 'SALES')
ON CONFLICT DO NOTHING;

INSERT INTO role_template_permissions (template_id, permission_code) VALUES
    -- Thu ngân: KHÔNG createWithDiscount, KHÔNG getContact
    ('00000000-0000-0000-0000-0000000000c1', 'PosOrderController/create'),
    ('00000000-0000-0000-0000-0000000000c1', 'SalesOrderController/search'),
    ('00000000-0000-0000-0000-0000000000c1', 'SalesOrderController/get'),
    ('00000000-0000-0000-0000-0000000000c1', 'SalesOrderController/receipt'),
    ('00000000-0000-0000-0000-0000000000c1', 'StockItemController/search'),
    ('00000000-0000-0000-0000-0000000000c1', 'StockItemController/get'),
    ('00000000-0000-0000-0000-0000000000c1', 'TitleController/lookup'),
    -- Thủ kho
    ('00000000-0000-0000-0000-0000000000c2', 'StockItemController/register'),
    ('00000000-0000-0000-0000-0000000000c2', 'StockItemController/search'),
    ('00000000-0000-0000-0000-0000000000c2', 'StockItemController/get'),
    ('00000000-0000-0000-0000-0000000000c2', 'StockItemController/updatePrice'),
    ('00000000-0000-0000-0000-0000000000c2', 'StockItemController/ledger'),
    ('00000000-0000-0000-0000-0000000000c2', 'StockAdjustmentController/adjust'),
    ('00000000-0000-0000-0000-0000000000c2', 'StockAdjustmentController/stocktake'),
    ('00000000-0000-0000-0000-0000000000c2', 'SupplierController/search'),
    ('00000000-0000-0000-0000-0000000000c2', 'SupplierController/create'),
    ('00000000-0000-0000-0000-0000000000c2', 'SupplierController/update'),
    ('00000000-0000-0000-0000-0000000000c2', 'GoodsReceiptController/create'),
    ('00000000-0000-0000-0000-0000000000c2', 'GoodsReceiptController/search'),
    ('00000000-0000-0000-0000-0000000000c2', 'GoodsReceiptController/get'),
    ('00000000-0000-0000-0000-0000000000c2', 'GoodsReceiptController/confirm'),
    ('00000000-0000-0000-0000-0000000000c2', 'GoodsReceiptController/cancel'),
    ('00000000-0000-0000-0000-0000000000c2', 'TitleController/lookup')
ON CONFLICT DO NOTHING;

-- Quản lý cửa hàng: mọi khóa sales (mọi permission có service = 'sales-service') và TitleController/lookup.
INSERT INTO role_template_permissions (template_id, permission_code)
SELECT '00000000-0000-0000-0000-0000000000c3', p.code FROM permissions p
WHERE p.service = 'sales-service' OR p.code = 'TitleController/lookup'
ON CONFLICT DO NOTHING;

-- ===== 3. Backfill: tenant ĐÃ bật SALES nhận role mẫu mới =====
-- Cùng ngữ nghĩa seedModuleTemplates: bỏ qua tên role đã có trong tenant (không ghi đè, không nhân bản), chỉ gán quyền
-- cho role vừa tạo. roles/role_permissions có RLS FORCE nên đặt app.tenant_id từng tenant (cục bộ transaction).
DO $$
DECLARE
    t        RECORD;
    tpl      RECORD;
    new_id   UUID;
    inserted INTEGER;
BEGIN
    FOR t IN SELECT tenant_id FROM tenant_modules WHERE module = 'SALES' AND enabled ORDER BY tenant_id LOOP
        PERFORM set_config('app.tenant_id', t.tenant_id::text, true);
        FOR tpl IN SELECT id, name, description, is_admin FROM role_templates WHERE module = 'SALES' ORDER BY name LOOP
            new_id := gen_random_uuid();
            INSERT INTO roles (id, name, description, is_admin, tenant_id)
            VALUES (new_id, tpl.name, tpl.description, tpl.is_admin, t.tenant_id)
            ON CONFLICT (tenant_id, name) DO NOTHING;
            GET DIAGNOSTICS inserted = ROW_COUNT;
            IF inserted > 0 THEN
                INSERT INTO role_permissions (role_id, permission_code)
                SELECT new_id, rtp.permission_code FROM role_template_permissions rtp WHERE rtp.template_id = tpl.id;
            END IF;
        END LOOP;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;
