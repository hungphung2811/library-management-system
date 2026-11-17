-- Bổ sung menu mặc định cho tenant mặc định: mục con của Sách, Mượn trả, Quản trị và các màn Bán hàng chưa có
-- (liên hệ khách, hoàn tiền, nhật ký, cấu hình). Phải khớp DefaultMenus (menu.application). Chạy lại an toàn: bỏ qua mục
-- đã có (UNIQUE tenant_id, code). Tenant khác đã chép menu từ trước tự thêm qua trang Quản trị > Menu.
-- menus đã FORCE ROW LEVEL SECURITY nên phải đặt app.tenant_id trong transaction của migration.
SELECT set_config('app.tenant_id', '00000000-0000-0000-0000-0000000000d1', true);

INSERT INTO menus (id, tenant_id, parent_id, code, label, path, sort_order, permission_key, module)
SELECT gen_random_uuid(), '00000000-0000-0000-0000-0000000000d1', p.id, v.code, v.label, v.path, v.sort_order,
       v.permission_key, v.module
  FROM (VALUES
    ('catalog-barcode', 'catalog', 'Tra mã vạch', '/catalog/barcode', 10, 'BookCopyController/getByBarcode', null),
    ('catalog-new', 'catalog', 'Thêm sách', '/catalog/books/new', 20, 'TitleController/create', null),
    ('circulation-borrow', 'circulation', 'Lập phiếu mượn', '/circulation/borrow', 10, 'BorrowingController/borrow', 'LENDING'),
    ('circulation-return', 'circulation', 'Nhận trả sách', '/circulation/return', 20, 'BorrowingController/returnBook', 'LENDING'),
    ('circulation-mine', 'circulation', 'Phiếu của tôi', '/circulation/mine', 30, null, 'LENDING'),
    ('circulation-borrowings', 'circulation', 'Tra phiếu mượn', '/circulation/borrowings', 40, 'BorrowingController/search', 'LENDING'),
    ('circulation-reservations', 'circulation', 'Đặt trước', '/circulation/reservations', 50, 'ReservationController/mine', 'LENDING'),
    ('circulation-fines', 'circulation', 'Phạt', '/circulation/fines', 60, 'FineController/byReader', 'LENDING'),
    ('admin-users', 'admin', 'Người dùng', '/admin/users', 10, 'UserController/search', null),
    ('admin-roles', 'admin', 'Vai trò và quyền', '/admin/roles', 20, 'AccessControlController/listRoles', null),
    ('admin-departments', 'admin', 'Phòng ban', '/admin/departments', 30, 'DepartmentController/tree', null),
    ('admin-audit-logs', 'admin', 'Nhật ký hoạt động', '/admin/audit-logs', 40, 'AuditLogController/search', null),
    ('admin-menus', 'admin', 'Menu', '/admin/menus', 50, 'MenuAdminController/listMenus', null),
    ('admin-contract', 'admin', 'Hợp đồng', '/admin/contract', 60, 'TenantContractController/current', null),
    ('admin-storefront-origins', 'admin', 'Website', '/admin/storefront-origins', 70, 'TenantStorefrontOriginController/list', 'SALES'),
    ('sales-contacts', 'sales', 'Liên hệ khách', '/sales/contacts', 25, 'SalesContactController/search', 'SALES'),
    ('sales-refunds', 'sales', 'Hoàn tiền', '/sales/refunds', 26, 'RefundController/search', 'SALES'),
    ('sales-audit', 'sales', 'Nhật ký bán hàng', '/sales/audit', 60, 'SalesAuditController/search', 'SALES'),
    ('sales-settings', 'sales', 'Cấu hình bán hàng', '/sales/settings', 70, 'SalesSettingsController/get', 'SALES')
  ) AS v(code, parent_code, label, path, sort_order, permission_key, module)
  JOIN menus p ON p.tenant_id = '00000000-0000-0000-0000-0000000000d1' AND p.code = v.parent_code
ON CONFLICT (tenant_id, code) DO NOTHING;
