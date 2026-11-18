package lms.sales.shared.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Đọc nhật ký bán hàng (nguồn chuẩn sales_audit_log). */
public interface SalesAuditQueryPort {

    PageResult<Entry> search(UUID tenantId, Criteria criteria, int page, int size);

    /**
     * Đã có dòng {@code PAYMENT_ANOMALY} của lệnh {@code intentId} với {@code data.anomalyType} này chưa (ADR-0029).
     * Đọc các dòng theo (action, aggregateType, aggregateId) rồi so {@code anomalyType} trong bộ nhớ (dùng index
     * aggregate có sẵn, không native query). Phải gọi khi đã gắn đúng tenant.
     */
    default boolean hasPaymentAnomaly(UUID tenantId, UUID intentId, String anomalyType) {
        PageResult<Entry> page = search(tenantId, new Criteria(SalesAuditPort.Actions.PAYMENT_ANOMALY, "PaymentIntent",
                intentId, null, null), 0, 200);
        return page.items().stream().anyMatch(e -> anomalyType.equals(e.data().get("anomalyType")));
    }

    /** from bao gồm, to loại trừ (theo occurredAt). */
    record Criteria(String action, String aggregateType, UUID aggregateId, Instant from, Instant to) {
    }

    record Entry(UUID id, String action, String aggregateType, UUID aggregateId, UUID actorId, Instant occurredAt,
                 Map<String, Object> data) {
    }
}
