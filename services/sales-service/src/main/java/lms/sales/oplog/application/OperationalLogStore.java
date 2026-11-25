package lms.sales.oplog.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.sales.oplog.domain.OperationalLogEvent;
import lms.sales.shared.application.PageResult;

/**
 * Cổng lưu trữ log vận hành. Đọc chịu RLS theo tenant của request (platform ở phạm vi ALL đọc được mọi dòng); ghi và dọn
 * do luồng nền gọi nên adapter tự chọn phạm vi tenant phù hợp.
 */
public interface OperationalLogStore {

    /** Tiêu chí lọc; {@code channels} không rỗng; {@code tenantId} null = không lọc thêm theo tenant (RLS vẫn áp). */
    record Criteria(UUID tenantId, List<String> channels, String provider, String outcome, String level,
                    boolean anomalyOnly, Instant from, Instant to) {
    }

    /** Ghi theo lô; dòng không có tenant chỉ platform đọc được. Không ném lỗi nghiệp vụ. */
    void saveBatch(List<OperationalLogEvent> events);

    /** Mới nhất trước; {@code from} bao gồm, {@code to} loại trừ. */
    PageResult<OperationalLogEvent> search(Criteria criteria, int page, int size);

    /** Xóa tối đa {@code limit} dòng có {@code occurredAt} trước {@code cutoff}; trả số dòng đã xóa. */
    int purgeOlderThan(Instant cutoff, int limit);
}
