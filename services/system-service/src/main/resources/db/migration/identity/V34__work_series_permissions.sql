-- ADR-0026 mục 3.12 (pha 1c): khóa quyền của tác phẩm (WorkController), bộ sách (SeriesController) và đổi tác phẩm của ấn bản
-- (TitleController/changeWork), backfill khóa ĐỌC cho role đang chạy và cập nhật mẫu role.
-- * 12 khóa: WorkController/{search,get,create,update,merge}, TitleController/changeWork,
--   SeriesController/{search,options,get,create,update,setStatus}. Tên method đã chốt trong ADR, KHÔNG đổi sau khi phát hành.
--   (SeriesImportController/{upload,confirm} thuộc pha nhập Excel 2e: chưa có controller nên chưa seed khóa.)
-- * Mẫu LIBRARY_ADMIN (a2), LIBRARIAN (a3), STORE_MANAGER (c3): đủ 12 khóa. Mọi mẫu khác đang có TitleController/search hoặc
--   TitleController/get nhận khóa đọc tương ứng (WorkController/search|get, SeriesController/search|options|get).
-- * Backfill role của tenant đang chạy (khác ADR-0024, theo khuyến nghị ADR-0026): role không phải admin đang có
--   TitleController/search nhận WorkController/search + SeriesController/search + SeriesController/options; đang có
--   TitleController/get nhận WorkController/get + SeriesController/get. Khóa GHI không backfill (admin tenant gán tay).
--   role_permissions có RLS theo tenant (V13): lặp từng tenant và đặt app.tenant_id. Quyền mới chỉ có hiệu lực từ lần đăng
--   nhập hoặc refresh token kế tiếp (ADR-0003).
-- * Số V34 (không phải V32 như ADR dự kiến): V32 (applications) đã bị phiên khác chiếm; V33 dành cho tính năng đền bù.
-- permissions, role_templates, role_template_permissions là bảng TOÀN CỤC (không RLS); chỉ phần backfill ghi role_permissions.

-- ===== 1. Khóa endpoint (đường dẫn theo hợp đồng BFF /api/works, /api/series, /api/books/{id}/work) =====
INSERT INTO permissions (code, description, source, service, http_method, path_pattern, first_seen_at)
SELECT k.code, k.http_method || ' ' || k.path_pattern || ' (' || k.service || ')', 'AUTO', k.service, k.http_method,
       k.path_pattern, now()
FROM (VALUES
    ('WorkController/search',         'catalog-service', 'GET',  '/api/works'),
    ('WorkController/get',            'catalog-service', 'GET',  '/api/works/{id}'),
    ('WorkController/create',         'catalog-service', 'POST', '/api/works'),
    ('WorkController/update',         'catalog-service', 'PUT',  '/api/works/{id}'),
    ('WorkController/merge',          'catalog-service', 'POST', '/api/works/{id}/merge'),
    ('TitleController/changeWork',    'catalog-service', 'PUT',  '/api/books/{id}/work'),
    ('SeriesController/search',       'catalog-service', 'GET',  '/api/series'),
    ('SeriesController/options',      'catalog-service', 'GET',  '/api/series/options'),
    ('SeriesController/get',          'catalog-service', 'GET',  '/api/series/{id}'),
    ('SeriesController/create',       'catalog-service', 'POST', '/api/series'),
    ('SeriesController/update',       'catalog-service', 'PUT',  '/api/series/{id}'),
    ('SeriesController/setStatus',    'catalog-service', 'PUT',  '/api/series/{id}/status')
) AS k (code, service, http_method, path_pattern)
ON CONFLICT (code) DO NOTHING;

-- ===== 2. Backfill khóa đọc cho role của tenant đang chạy (trước khi cập nhật mẫu) =====
DO $$
DECLARE
    t RECORD;
BEGIN
    FOR t IN SELECT id FROM tenants ORDER BY id LOOP
        PERFORM set_config('app.tenant_id', t.id::text, true);
        INSERT INTO role_permissions (role_id, permission_code)
        SELECT DISTINCT rp.role_id, m.new_key
        FROM role_permissions rp
        JOIN roles r ON r.id = rp.role_id AND r.tenant_id = t.id
        JOIN (VALUES
            ('TitleController/search', 'WorkController/search'),
            ('TitleController/search', 'SeriesController/search'),
            ('TitleController/search', 'SeriesController/options'),
            ('TitleController/get',    'WorkController/get'),
            ('TitleController/get',    'SeriesController/get')
        ) AS m (old_key, new_key) ON m.old_key = rp.permission_code
        WHERE NOT r.is_admin
        ON CONFLICT DO NOTHING;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;

-- ===== 3. Cập nhật mẫu (tenant tạo mới từ mẫu nhận khóa mới) =====
INSERT INTO role_template_permissions (template_id, permission_code)
SELECT tpl.id, k.code
FROM (VALUES
    ('00000000-0000-0000-0000-0000000000a2'::uuid), -- LIBRARY_ADMIN
    ('00000000-0000-0000-0000-0000000000a3'::uuid), -- LIBRARIAN
    ('00000000-0000-0000-0000-0000000000c3'::uuid)  -- STORE_MANAGER
) AS tpl (id)
CROSS JOIN (VALUES
    ('WorkController/search'), ('WorkController/get'), ('WorkController/create'), ('WorkController/update'),
    ('WorkController/merge'), ('TitleController/changeWork'), ('SeriesController/search'),
    ('SeriesController/options'), ('SeriesController/get'), ('SeriesController/create'), ('SeriesController/update'),
    ('SeriesController/setStatus')
) AS k (code)
ON CONFLICT DO NOTHING;

-- Mọi mẫu khác đang có TitleController/search hoặc TitleController/get nhận khóa đọc tương ứng
INSERT INTO role_template_permissions (template_id, permission_code)
SELECT DISTINCT rtp.template_id, m.new_key
FROM role_template_permissions rtp
JOIN (VALUES
    ('TitleController/search', 'WorkController/search'),
    ('TitleController/search', 'SeriesController/search'),
    ('TitleController/search', 'SeriesController/options'),
    ('TitleController/get',    'WorkController/get'),
    ('TitleController/get',    'SeriesController/get')
) AS m (old_key, new_key) ON m.old_key = rtp.permission_code
ON CONFLICT DO NOTHING;
