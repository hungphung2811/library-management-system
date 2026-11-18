-- Khóa quyền cho chi tiết quản lý mặt hàng (giá nhập, ghi chú nội bộ) và ghi chú đơn (staffNote) của sales-service.
-- * Chi tiết mặt hàng: Thủ kho (c2) và Quản lý cửa hàng (c3). Ghi chú đơn: Quản lý cửa hàng (c3). Thu ngân (c1) và Khách
--   hàng (c4): không có, nên không thấy costPrice/note/staffNote (các trường này không có ở get/search).
-- * Chỉ cập nhật bảng TOÀN CỤC (permissions, role_template_permissions): không backfill role của tenant đang chạy
--   (role đã tạo từ mẫu không tự nhận khóa mới): tenant admin gán tay 4 khóa dưới đây nếu cần.
INSERT INTO permissions (code, description, source, service, http_method, path_pattern, first_seen_at)
SELECT k.code, k.http_method || ' ' || k.path_pattern || ' (' || k.service || ')', 'AUTO', k.service, k.http_method,
       k.path_pattern, now()
FROM (VALUES
    ('StockItemController/getDetails',    'sales-service', 'GET', '/api/sales/stock-items/{titleId}/details'),
    ('StockItemController/updateDetails', 'sales-service', 'PUT', '/api/sales/stock-items/{titleId}/details'),
    ('SalesOrderController/getNotes',     'sales-service', 'GET', '/api/sales/orders/{id}/notes'),
    ('SalesOrderController/updateNotes',  'sales-service', 'PUT', '/api/sales/orders/{id}/notes')
) AS k (code, service, http_method, path_pattern)
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_template_permissions (template_id, permission_code) VALUES
    ('00000000-0000-0000-0000-0000000000c2', 'StockItemController/getDetails'),
    ('00000000-0000-0000-0000-0000000000c2', 'StockItemController/updateDetails'),
    ('00000000-0000-0000-0000-0000000000c3', 'StockItemController/getDetails'),
    ('00000000-0000-0000-0000-0000000000c3', 'StockItemController/updateDetails'),
    ('00000000-0000-0000-0000-0000000000c3', 'SalesOrderController/getNotes'),
    ('00000000-0000-0000-0000-0000000000c3', 'SalesOrderController/updateNotes')
ON CONFLICT DO NOTHING;
