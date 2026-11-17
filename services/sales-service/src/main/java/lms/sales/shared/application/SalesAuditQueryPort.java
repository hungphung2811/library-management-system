package lms.sales.shared.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Đọc nhật ký bán hàng (nguồn chuẩn sales_audit_log). */
public interface SalesAuditQueryPort {

    PageResult<Entry> search(UUID tenantId, Criteria criteria, int page, int size);

    /** from bao gồm, to loại trừ (theo occurredAt). */
    record Criteria(String action, String aggregateType, UUID aggregateId, Instant from, Instant to) {
    }

    record Entry(UUID id, String action, String aggregateType, UUID aggregateId, UUID actorId, Instant occurredAt,
                 Map<String, Object> data) {
    }
}
