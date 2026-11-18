-- Sửa RISK-1 của V35: duyệt hoàn khoản (FineRefundController/approve) chuyển bản sao LOST về kho bằng chính token của người
-- duyệt (CatalogCopyRestClientAdapter.changeStatus và get để lấy giá) nên catalog đòi khóa BookCopyController/changeStatus
-- và BookCopyController/get. Mẫu LIBRARY_ADMIN (a2, không is_admin) có approve nhưng thiếu hai khóa này: duyệt bị catalog
-- 403 và rollback. Thêm migration mới vì V35 đã chạy ở DB dev (không sửa file đã áp, đổi checksum).
-- * Hai khóa này chỉ tự đăng ký lúc chạy (chưa có migration nào tạo), nên DB mới chưa có: tạo trước (ON CONFLICT DO NOTHING,
--   DB dev đã có do tự đăng ký). Metadata khớp bản tự đăng ký của catalog-service.
-- * Mọi mẫu được gán FineRefundController/approve: chỉ a2. Khóa catalog đã có trong permissions từ V9.
-- * Chỉ bảng toàn cục role_template_permissions: không backfill role của tenant đang chạy (tenant admin gán tay).
INSERT INTO permissions (code, description, source, service, http_method, path_pattern, first_seen_at)
SELECT k.code, k.http_method || ' ' || k.path_pattern || ' (' || k.service || ')', 'AUTO', k.service, k.http_method,
       k.path_pattern, now()
FROM (VALUES
    ('BookCopyController/get',          'catalog-service', 'GET', '/internal/book-copies/{id}'),
    ('BookCopyController/changeStatus', 'catalog-service', 'PUT', '/internal/book-copies/{id}/status')
) AS k (code, service, http_method, path_pattern)
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_template_permissions (template_id, permission_code)
SELECT t.template_id, k.code
FROM (SELECT DISTINCT template_id FROM role_template_permissions
      WHERE permission_code = 'FineRefundController/approve') t
CROSS JOIN (VALUES ('BookCopyController/changeStatus'), ('BookCopyController/get')) AS k (code)
ON CONFLICT DO NOTHING;
