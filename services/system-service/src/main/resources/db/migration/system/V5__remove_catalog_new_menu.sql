-- "Thêm sách" không còn là một màn riêng: form thêm sách là modal mở từ trang Sách (/catalog?new=1), nên bỏ mục menu
-- trỏ tới /catalog/books/new (V4 đã thêm). Phải khớp DefaultMenus. menus đã FORCE ROW LEVEL SECURITY: đặt app.tenant_id
-- của tenant mặc định trong transaction của migration. Tenant khác đã chép menu từ trước tự xóa mục này ở Quản trị > Menu.
SELECT set_config('app.tenant_id', '00000000-0000-0000-0000-0000000000d1', true);

DELETE FROM menus WHERE tenant_id = '00000000-0000-0000-0000-0000000000d1' AND code = 'catalog-new';
