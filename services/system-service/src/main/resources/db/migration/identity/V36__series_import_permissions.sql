-- ADR-0026 mục 3.8 và 3.12 (pha 2e): khóa quyền của loại nhập Excel mới "series" (bộ sách), khuôn V31.
-- * Hai khóa luôn gán cùng nhau: SeriesImportController/upload và SeriesImportController/confirm (template/get/rows/cancel/
--   error-file chỉ cần đăng nhập và kiểm chủ phiên nên KHÔNG có khóa). Tên lớp/method đã chốt trong ADR, KHÔNG đổi sau khi
--   phát hành (đổi tên đổi khóa, role đã gán mất quyền).
-- * Vai trò mẫu nhận cả hai khóa: LIBRARY_ADMIN (a2), LIBRARIAN (a3), STORE_MANAGER (c3) như các loại catalog khác (V31).
-- * Chỉ cập nhật bảng TOÀN CỤC (permissions, role_template_permissions): KHÔNG backfill role của tenant đang chạy (giống
--   ADR-0024: khóa ghi do admin tenant gán tay). Role is_admin bỏ qua kiểm khóa.
-- * path_pattern theo đường dẫn FE (/api/catalog/imports/series[...]).
-- * Số V36: V34 (khóa tác phẩm/bộ sách) và V35 (đền bù) đã có; kiểm số kế tiếp trước khi tạo.
INSERT INTO permissions (code, description, source, service, http_method, path_pattern, first_seen_at)
SELECT k.code, k.http_method || ' ' || k.path_pattern || ' (' || k.service || ')', 'AUTO', k.service, k.http_method,
       k.path_pattern, now()
FROM (VALUES
    ('SeriesImportController/upload',  'catalog-service', 'POST', '/api/catalog/imports/series'),
    ('SeriesImportController/confirm', 'catalog-service', 'POST', '/api/catalog/imports/series/{importId}/confirm')
) AS k (code, service, http_method, path_pattern)
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_template_permissions (template_id, permission_code) VALUES
    ('00000000-0000-0000-0000-0000000000a2', 'SeriesImportController/upload'),  -- LIBRARY_ADMIN
    ('00000000-0000-0000-0000-0000000000a2', 'SeriesImportController/confirm'),
    ('00000000-0000-0000-0000-0000000000a3', 'SeriesImportController/upload'),  -- LIBRARIAN
    ('00000000-0000-0000-0000-0000000000a3', 'SeriesImportController/confirm'),
    ('00000000-0000-0000-0000-0000000000c3', 'SeriesImportController/upload'),  -- STORE_MANAGER
    ('00000000-0000-0000-0000-0000000000c3', 'SeriesImportController/confirm')
ON CONFLICT DO NOTHING;
