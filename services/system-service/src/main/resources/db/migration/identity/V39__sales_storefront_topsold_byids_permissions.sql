-- Khóa endpoint storefront mới của sales-service (ADR-0029): top bán chạy và thẻ sách theo danh sách id.
-- Cả hai là @StorefrontAccessible (token dịch vụ customer-api, không kiểm khóa): chỉ ĐĂNG KÝ để màn hình phân quyền
-- thấy, KHÔNG gán cho mẫu role nào (như StorefrontController/suggestions ở V26). Đường dẫn service nguyên trạng.
-- Các endpoint "của tôi" mới (ShopController/myOrderCounts, ShopPaymentController/*) là @AuthenticatedOnly nên KHÔNG có khóa.
-- permissions là bảng TOÀN CỤC (không RLS).
INSERT INTO permissions (code, description, source, service, http_method, path_pattern, first_seen_at)
SELECT k.code, k.http_method || ' ' || k.path_pattern || ' (' || k.service || ')', 'AUTO', k.service, k.http_method,
       k.path_pattern, now()
FROM (VALUES
    ('StorefrontTopSoldController/topSold', 'sales-service', 'GET', '/internal/sales/storefront/top-sold'),
    ('StorefrontController/byIds',          'sales-service', 'GET', '/internal/sales/storefront/items/by-ids')
) AS k (code, service, http_method, path_pattern)
ON CONFLICT (code) DO NOTHING;
