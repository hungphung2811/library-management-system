-- Rà soát index (docs/architecture/ra-soat-n-plus-1-va-index-sales-identity-support.md). Chỉ DDL, bảng giữ RLS+FORCE.

-- AuditLogPersistenceAdapter.search không lọc: where tenant_id = :t order by occurred_at desc, id (màn Nhật ký mặc định).
-- V2 chỉ còn index (tenant_id, aggregate_id, ...) và (tenant_id, event_type, ...), nên truy vấn không lọc phải sắp xếp lại
-- toàn bộ audit_logs của tenant (bảng chỉ thêm, không bao giờ xoá).
CREATE INDEX idx_audit_logs_tenant_occurred ON audit_logs (tenant_id, occurred_at DESC, id);

-- SpringDataNotificationRepository.countByUserIdAndReadAtIsNull (huy hiệu chưa đọc, gọi lặp theo chu kỳ cho mỗi người dùng)
-- và markAllRead: where tenant_id = :t and user_id = :u and read_at is null. Index một phần chỉ chứa thông báo chưa đọc
-- nên nhỏ và không phình theo lịch sử; idx_notifications_tenant_user vẫn phục vụ danh sách đầy đủ.
CREATE INDEX idx_notifications_tenant_user_unread ON notifications (tenant_id, user_id) WHERE read_at IS NULL;
