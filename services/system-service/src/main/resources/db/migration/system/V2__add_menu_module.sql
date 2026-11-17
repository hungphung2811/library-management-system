-- Menu theo module (ADR-0009 mục 2.5): menus.module NULL = mục lõi (luôn hiện); LENDING/SALES chỉ hiện khi tenant
-- được bật module tương ứng (lọc trong MenuTree.visibleFor theo claim JWT "modules").
ALTER TABLE menus ADD COLUMN module VARCHAR(20) NULL;
ALTER TABLE menus ADD CONSTRAINT ck_menus_module CHECK (module IS NULL OR module IN ('LENDING', 'SALES'));

-- menus đã FORCE ROW LEVEL SECURITY: không đặt app.tenant_id thì UPDATE khớp 0 dòng mà không báo lỗi, INSERT bị chặn.
-- set_config(..., true) chỉ có hiệu lực trong transaction của migration này.
SELECT set_config('app.tenant_id', '00000000-0000-0000-0000-0000000000d1', true);

-- Tenant mặc định (chỉ bật LENDING): "Mượn trả" thuộc LENDING; thêm nhánh Bán hàng (SALES, bị ẩn tới khi bật module).
-- Phải khớp DefaultMenus (menu.application); khóa quyền theo đề xuất ở docs/architecture/sales-module-design.md mục 5.
UPDATE menus SET module = 'LENDING'
 WHERE tenant_id = '00000000-0000-0000-0000-0000000000d1' AND code = 'circulation';

INSERT INTO menus (id, tenant_id, parent_id, code, label, path, sort_order, permission_key, module) VALUES
    ('00000000-0000-0000-0000-00000000e101', '00000000-0000-0000-0000-0000000000d1', NULL, 'sales',
     'Bán hàng', NULL, 50, NULL, 'SALES'),
    ('00000000-0000-0000-0000-00000000e102', '00000000-0000-0000-0000-0000000000d1',
     '00000000-0000-0000-0000-00000000e101', 'sales-pos', 'Bán tại quầy', '/sales/pos', 10,
     'PosOrderController/create', 'SALES'),
    ('00000000-0000-0000-0000-00000000e103', '00000000-0000-0000-0000-0000000000d1',
     '00000000-0000-0000-0000-00000000e101', 'sales-orders', 'Đơn hàng', '/sales/orders', 20,
     'SalesOrderController/search', 'SALES'),
    ('00000000-0000-0000-0000-00000000e104', '00000000-0000-0000-0000-0000000000d1',
     '00000000-0000-0000-0000-00000000e101', 'sales-stock', 'Tồn kho', '/sales/stock', 30,
     'StockItemController/search', 'SALES'),
    ('00000000-0000-0000-0000-00000000e105', '00000000-0000-0000-0000-0000000000d1',
     '00000000-0000-0000-0000-00000000e101', 'sales-receipts', 'Nhập hàng', '/sales/receipts', 40,
     'GoodsReceiptController/search', 'SALES'),
    ('00000000-0000-0000-0000-00000000e106', '00000000-0000-0000-0000-0000000000d1',
     '00000000-0000-0000-0000-00000000e101', 'sales-suppliers', 'Nhà cung cấp', '/sales/suppliers', 50,
     'SupplierController/search', 'SALES');
