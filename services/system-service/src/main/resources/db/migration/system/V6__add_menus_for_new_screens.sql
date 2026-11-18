-- Menu cho các màn mới (tác phẩm, đền bù, yêu cầu miễn giảm/hoàn phạt, banner, báo cáo) và đưa "Quản trị" xuống sau các
-- menu nghiệp vụ (sort_order 40 -> 60). Phải khớp DefaultMenus. menus FORCE ROW LEVEL SECURITY: đặt app.tenant_id của
-- tenant mặc định trong transaction của migration. Chạy lại an toàn (UNIQUE tenant_id, code). Tenant khác đã chép menu
-- từ trước tự chỉnh ở Quản trị > Menu.
SELECT set_config('app.tenant_id', '00000000-0000-0000-0000-0000000000d1', true);

UPDATE menus SET sort_order = 60
 WHERE tenant_id = '00000000-0000-0000-0000-0000000000d1' AND code = 'admin' AND parent_id IS NULL;

INSERT INTO menus (id, tenant_id, parent_id, code, label, path, sort_order, permission_key, module)
SELECT gen_random_uuid(), '00000000-0000-0000-0000-0000000000d1', NULL, 'reports', 'Báo cáo', '/reports', 55, 'LendingReportController/overdueBorrowingsCsv', NULL
ON CONFLICT (tenant_id, code) DO NOTHING;

INSERT INTO menus (id, tenant_id, parent_id, code, label, path, sort_order, permission_key, module)
SELECT gen_random_uuid(), '00000000-0000-0000-0000-0000000000d1', p.id, v.code, v.label, v.path, v.sort_order,
       v.permission_key, v.module
  FROM (VALUES
    ('catalog-works', 'catalog', 'Tác phẩm', '/catalog/works', 20, 'WorkController/search', null),
    ('circulation-compensation', 'circulation', 'Đền bù và mức hư hại', '/circulation/compensation', 70, 'CompensationPolicyController/get', 'LENDING'),
    ('circulation-fine-requests', 'circulation', 'Yêu cầu miễn giảm, hoàn phạt', '/circulation/fine-requests', 80, 'FineAdjustmentController/list', 'LENDING'),
    ('sales-banners', 'sales', 'Banner cửa hàng', '/sales/banners', 65, 'SalesBannerController/list', 'SALES')
  ) AS v(code, parent_code, label, path, sort_order, permission_key, module)
  JOIN menus p ON p.tenant_id = '00000000-0000-0000-0000-0000000000d1' AND p.code = v.parent_code
ON CONFLICT (tenant_id, code) DO NOTHING;
