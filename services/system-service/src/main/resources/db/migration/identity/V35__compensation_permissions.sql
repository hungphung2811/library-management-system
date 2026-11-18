-- Khóa quyền cho tình trạng sách và chính sách đền bù của circulation-service
-- (docs/requirements/2026-11-18-tinh-trang-sach-va-den-bu.md mục 8): 14 khóa.
-- * Đánh số V35 vì V34 (sách ba tầng) đã có; V33 bỏ trống để không chèn vào sau một migration có thể đã chạy.
-- * Tách khóa đề nghị và khóa duyệt: FineAdjustmentController/request (đề nghị miễn/giảm) khác approve/reject; hoàn khoản:
--   BorrowingController/reportFound (ghi nhận tìm lại, đề nghị hoàn) khác FineRefundController/approve|reject. Ngoài khóa,
--   use case còn kiểm người duyệt khác người đề nghị (APPROVER_MUST_DIFFER), không dựa vào role.
-- * Vai trò mẫu: LIBRARIAN (a3) là thủ thư đề nghị (đề nghị miễn/giảm, ghi nhận tìm lại, xem danh sách, xem bậc hỏng và
--   chính sách đền bù); LIBRARY_ADMIN (a2) là quản lý duyệt (duyệt/từ chối cả hai loại) và cấu hình (bậc hỏng, chính
--   sách đền bù), kèm các khóa của thủ thư. Role is_admin bỏ qua kiểm khóa. Trả/báo mất có bậc, ghi chú, ảnh dùng khóa
--   hiện có BorrowingController/returnCopy|returnBook|reportLost.
-- * Chỉ cập nhật bảng TOÀN CỤC (permissions, role_template_permissions): không backfill role của tenant đang chạy (role đã
--   tạo từ mẫu không tự nhận khóa mới): tenant admin gán tay các khóa cần dùng.
-- * path_pattern theo đường dẫn FE (/api/...). Đổi tên controller/method làm đổi khóa nên KHÔNG đổi sau khi phát hành.
INSERT INTO permissions (code, description, source, service, http_method, path_pattern, first_seen_at)
SELECT k.code, k.http_method || ' ' || k.path_pattern || ' (' || k.service || ')', 'AUTO', k.service, k.http_method,
       k.path_pattern, now()
FROM (VALUES
    ('DamageLevelController/list',                   'circulation-service', 'GET',    '/api/circulation/damage-levels'),
    ('DamageLevelController/create',                 'circulation-service', 'POST',   '/api/circulation/damage-levels'),
    ('DamageLevelController/update',                 'circulation-service', 'PUT',    '/api/circulation/damage-levels/{code}'),
    ('DamageLevelController/delete',                 'circulation-service', 'DELETE', '/api/circulation/damage-levels/{code}'),
    ('CompensationPolicyController/get',             'circulation-service', 'GET',    '/api/circulation/compensation-policy'),
    ('CompensationPolicyController/update',          'circulation-service', 'PUT',    '/api/circulation/compensation-policy'),
    ('FineAdjustmentController/request',             'circulation-service', 'POST',   '/api/fines/{id}/adjustment-requests'),
    ('FineAdjustmentController/list',                'circulation-service', 'GET',    '/api/fines/adjustment-requests'),
    ('FineAdjustmentController/approve',             'circulation-service', 'POST',   '/api/fines/adjustment-requests/{requestId}/approve'),
    ('FineAdjustmentController/reject',              'circulation-service', 'POST',   '/api/fines/adjustment-requests/{requestId}/reject'),
    ('FineRefundController/list',                    'circulation-service', 'GET',    '/api/fines/refund-requests'),
    ('FineRefundController/approve',                 'circulation-service', 'POST',   '/api/fines/refund-requests/{id}/approve'),
    ('FineRefundController/reject',                  'circulation-service', 'POST',   '/api/fines/refund-requests/{id}/reject'),
    ('BorrowingController/reportFound',              'circulation-service', 'POST',   '/api/borrowings/found')
) AS k (code, service, http_method, path_pattern)
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_template_permissions (template_id, permission_code) VALUES
    -- LIBRARIAN (a3): thủ thư đề nghị
    ('00000000-0000-0000-0000-0000000000a3', 'DamageLevelController/list'),
    ('00000000-0000-0000-0000-0000000000a3', 'CompensationPolicyController/get'),
    ('00000000-0000-0000-0000-0000000000a3', 'FineAdjustmentController/request'),
    ('00000000-0000-0000-0000-0000000000a3', 'FineAdjustmentController/list'),
    ('00000000-0000-0000-0000-0000000000a3', 'FineRefundController/list'),
    ('00000000-0000-0000-0000-0000000000a3', 'BorrowingController/reportFound'),
    -- LIBRARY_ADMIN (a2): quản lý duyệt + cấu hình, kèm khóa của thủ thư
    ('00000000-0000-0000-0000-0000000000a2', 'DamageLevelController/list'),
    ('00000000-0000-0000-0000-0000000000a2', 'DamageLevelController/create'),
    ('00000000-0000-0000-0000-0000000000a2', 'DamageLevelController/update'),
    ('00000000-0000-0000-0000-0000000000a2', 'DamageLevelController/delete'),
    ('00000000-0000-0000-0000-0000000000a2', 'CompensationPolicyController/get'),
    ('00000000-0000-0000-0000-0000000000a2', 'CompensationPolicyController/update'),
    ('00000000-0000-0000-0000-0000000000a2', 'FineAdjustmentController/request'),
    ('00000000-0000-0000-0000-0000000000a2', 'FineAdjustmentController/list'),
    ('00000000-0000-0000-0000-0000000000a2', 'FineAdjustmentController/approve'),
    ('00000000-0000-0000-0000-0000000000a2', 'FineAdjustmentController/reject'),
    ('00000000-0000-0000-0000-0000000000a2', 'FineRefundController/list'),
    ('00000000-0000-0000-0000-0000000000a2', 'FineRefundController/approve'),
    ('00000000-0000-0000-0000-0000000000a2', 'FineRefundController/reject'),
    ('00000000-0000-0000-0000-0000000000a2', 'BorrowingController/reportFound')
ON CONFLICT DO NOTHING;
