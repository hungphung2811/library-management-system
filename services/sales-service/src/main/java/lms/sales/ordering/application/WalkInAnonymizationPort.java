package lms.sales.ordering.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Ẩn danh tên và điện thoại khách vãng lai (ADR-0010 mục 2.8). Chỉ đơn đã kết thúc (COMPLETED hoặc CANCELLED) trước
 * mốc, còn tên hoặc điện thoại và chưa ẩn danh. Đơn, dòng, tiền giữ nguyên. Chạy trong TenantContext của tenant.
 */
public interface WalkInAnonymizationPort {

    /** Tối đa {@code limit} id đơn đến hạn ẩn danh (cũ nhất trước). */
    List<UUID> findDue(UUID tenantId, Instant cutoff, int limit);

    /** Xóa tên/điện thoại và đặt piiAnonymizedAt; trả số đơn đã đổi (idempotent: đơn đã ẩn danh không bị đếm). */
    int anonymize(UUID tenantId, List<UUID> orderIds, Instant now);
}
