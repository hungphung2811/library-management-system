-- Khóa quyền cho nhập master data hàng loạt từ Excel (ADR-0024 mục 4.1): 17 khóa import + TitleController/lookupByIsbn.
-- * Mỗi loại dữ liệu có hai khóa luôn gán cùng nhau: XImportController/upload và XImportController/confirm (các endpoint
--   template/get/rows/cancel/error-file chỉ cần đăng nhập và kiểm chủ phiên nên KHÔNG có khóa). Riêng người dùng thêm
--   UserImportController/credentials (tải một lần file mật khẩu tạm).
-- * Vai trò mẫu: LIBRARY_ADMIN (a2) và LIBRARIAN (a3): đầu sách, tác giả, NXB, thể loại, vị trí kệ; STORE_MANAGER (c3):
--   đầu sách, tác giả, NXB, thể loại (không có vị trí kệ) và mặt hàng, nhà cung cấp; STOCK_KEEPER (c2): mặt hàng, nhà
--   cung cấp; chỉ LIBRARY_ADMIN được nhập người dùng. TitleController/lookupByIsbn (catalog, sales gọi bằng token người
--   dùng khi nhập mặt hàng): c2, c3.
-- * Chỉ cập nhật bảng TOÀN CỤC (permissions, role_template_permissions): không backfill role của tenant đang chạy (role đã
--   tạo từ mẫu không tự nhận khóa mới): tenant admin gán tay các khóa cần dùng. Role is_admin bỏ qua kiểm khóa.
-- * path_pattern theo đường dẫn FE (/api/<module>/imports/<type>[...]) như các migration trước; đổi tên controller/method
--   làm đổi khóa nên KHÔNG đổi sau khi phát hành.
INSERT INTO permissions (code, description, source, service, http_method, path_pattern, first_seen_at)
SELECT k.code, k.http_method || ' ' || k.path_pattern || ' (' || k.service || ')', 'AUTO', k.service, k.http_method,
       k.path_pattern, now()
FROM (VALUES
    ('TitleImportController/upload',          'catalog-service',  'POST', '/api/catalog/imports/titles'),
    ('TitleImportController/confirm',         'catalog-service',  'POST', '/api/catalog/imports/titles/{importId}/confirm'),
    ('AuthorImportController/upload',         'catalog-service',  'POST', '/api/catalog/imports/authors'),
    ('AuthorImportController/confirm',        'catalog-service',  'POST', '/api/catalog/imports/authors/{importId}/confirm'),
    ('PublisherImportController/upload',      'catalog-service',  'POST', '/api/catalog/imports/publishers'),
    ('PublisherImportController/confirm',     'catalog-service',  'POST', '/api/catalog/imports/publishers/{importId}/confirm'),
    ('CategoryImportController/upload',       'catalog-service',  'POST', '/api/catalog/imports/categories'),
    ('CategoryImportController/confirm',      'catalog-service',  'POST', '/api/catalog/imports/categories/{importId}/confirm'),
    ('ShelfLocationImportController/upload',  'catalog-service',  'POST', '/api/catalog/imports/shelf-locations'),
    ('ShelfLocationImportController/confirm', 'catalog-service',  'POST', '/api/catalog/imports/shelf-locations/{importId}/confirm'),
    ('UserImportController/upload',           'identity-service', 'POST', '/api/identity/imports/users'),
    ('UserImportController/confirm',          'identity-service', 'POST', '/api/identity/imports/users/{importId}/confirm'),
    ('UserImportController/credentials',      'identity-service', 'GET',  '/api/identity/imports/users/{importId}/credentials'),
    ('StockItemImportController/upload',      'sales-service',    'POST', '/api/sales/imports/stock-items'),
    ('StockItemImportController/confirm',     'sales-service',    'POST', '/api/sales/imports/stock-items/{importId}/confirm'),
    ('SupplierImportController/upload',       'sales-service',    'POST', '/api/sales/imports/suppliers'),
    ('SupplierImportController/confirm',      'sales-service',    'POST', '/api/sales/imports/suppliers/{importId}/confirm'),
    ('TitleController/lookupByIsbn',          'catalog-service',  'POST', '/api/books/lookup-by-isbn')
) AS k (code, service, http_method, path_pattern)
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_template_permissions (template_id, permission_code) VALUES
    -- LIBRARY_ADMIN (a2): catalog (5 loại) + người dùng
    ('00000000-0000-0000-0000-0000000000a2', 'TitleImportController/upload'),
    ('00000000-0000-0000-0000-0000000000a2', 'TitleImportController/confirm'),
    ('00000000-0000-0000-0000-0000000000a2', 'AuthorImportController/upload'),
    ('00000000-0000-0000-0000-0000000000a2', 'AuthorImportController/confirm'),
    ('00000000-0000-0000-0000-0000000000a2', 'PublisherImportController/upload'),
    ('00000000-0000-0000-0000-0000000000a2', 'PublisherImportController/confirm'),
    ('00000000-0000-0000-0000-0000000000a2', 'CategoryImportController/upload'),
    ('00000000-0000-0000-0000-0000000000a2', 'CategoryImportController/confirm'),
    ('00000000-0000-0000-0000-0000000000a2', 'ShelfLocationImportController/upload'),
    ('00000000-0000-0000-0000-0000000000a2', 'ShelfLocationImportController/confirm'),
    ('00000000-0000-0000-0000-0000000000a2', 'UserImportController/upload'),
    ('00000000-0000-0000-0000-0000000000a2', 'UserImportController/confirm'),
    ('00000000-0000-0000-0000-0000000000a2', 'UserImportController/credentials'),
    -- LIBRARIAN (a3): catalog (5 loại)
    ('00000000-0000-0000-0000-0000000000a3', 'TitleImportController/upload'),
    ('00000000-0000-0000-0000-0000000000a3', 'TitleImportController/confirm'),
    ('00000000-0000-0000-0000-0000000000a3', 'AuthorImportController/upload'),
    ('00000000-0000-0000-0000-0000000000a3', 'AuthorImportController/confirm'),
    ('00000000-0000-0000-0000-0000000000a3', 'PublisherImportController/upload'),
    ('00000000-0000-0000-0000-0000000000a3', 'PublisherImportController/confirm'),
    ('00000000-0000-0000-0000-0000000000a3', 'CategoryImportController/upload'),
    ('00000000-0000-0000-0000-0000000000a3', 'CategoryImportController/confirm'),
    ('00000000-0000-0000-0000-0000000000a3', 'ShelfLocationImportController/upload'),
    ('00000000-0000-0000-0000-0000000000a3', 'ShelfLocationImportController/confirm'),
    -- STOCK_KEEPER (c2): mặt hàng, nhà cung cấp, tra ISBN
    ('00000000-0000-0000-0000-0000000000c2', 'StockItemImportController/upload'),
    ('00000000-0000-0000-0000-0000000000c2', 'StockItemImportController/confirm'),
    ('00000000-0000-0000-0000-0000000000c2', 'SupplierImportController/upload'),
    ('00000000-0000-0000-0000-0000000000c2', 'SupplierImportController/confirm'),
    ('00000000-0000-0000-0000-0000000000c2', 'TitleController/lookupByIsbn'),
    -- STORE_MANAGER (c3): đầu sách, tác giả, NXB, thể loại + mặt hàng, nhà cung cấp, tra ISBN
    ('00000000-0000-0000-0000-0000000000c3', 'TitleImportController/upload'),
    ('00000000-0000-0000-0000-0000000000c3', 'TitleImportController/confirm'),
    ('00000000-0000-0000-0000-0000000000c3', 'AuthorImportController/upload'),
    ('00000000-0000-0000-0000-0000000000c3', 'AuthorImportController/confirm'),
    ('00000000-0000-0000-0000-0000000000c3', 'PublisherImportController/upload'),
    ('00000000-0000-0000-0000-0000000000c3', 'PublisherImportController/confirm'),
    ('00000000-0000-0000-0000-0000000000c3', 'CategoryImportController/upload'),
    ('00000000-0000-0000-0000-0000000000c3', 'CategoryImportController/confirm'),
    ('00000000-0000-0000-0000-0000000000c3', 'StockItemImportController/upload'),
    ('00000000-0000-0000-0000-0000000000c3', 'StockItemImportController/confirm'),
    ('00000000-0000-0000-0000-0000000000c3', 'SupplierImportController/upload'),
    ('00000000-0000-0000-0000-0000000000c3', 'SupplierImportController/confirm'),
    ('00000000-0000-0000-0000-0000000000c3', 'TitleController/lookupByIsbn')
ON CONFLICT DO NOTHING;
