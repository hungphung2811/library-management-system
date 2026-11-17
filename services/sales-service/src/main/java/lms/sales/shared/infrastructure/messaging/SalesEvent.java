package lms.sales.shared.infrastructure.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lms.common.kernel.DomainEvent;
import lms.sales.shared.application.SalesAuditPort;

/**
 * Bản tóm tắt một dòng audit để phát lên sales.events sau commit (best-effort, ADR-0010 mục 2.6). {@code eventId} =
 * id dòng sales_audit_log. {@code data} chỉ chứa id/số tiền/số lượng/mã trạng thái: không có dữ liệu cá nhân.
 * Không dùng để trừ tồn hay ghi tiền: nguồn chuẩn là sales_db.
 */
public record SalesEvent(UUID eventId, String eventType, String aggregateId, Instant occurredAt, String action,
                         String aggregateType, UUID actorId, Map<String, Object> data) implements DomainEvent {

    public SalesEvent {
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static SalesEvent of(UUID eventId, SalesAuditPort.AuditEntry e) {
        return new SalesEvent(eventId, eventTypeOf(e.action(), e.data()),
                e.aggregateId() == null ? eventId.toString() : e.aggregateId().toString(), e.occurredAt(),
                e.action(), e.aggregateType(), e.actorId(), e.data());
    }

    /** Đơn online mới đặt là OrderPlaced (chưa hoàn tất); còn lại như {@link #eventTypeOf(String)}. */
    static String eventTypeOf(String action, Map<String, Object> data) {
        if (SalesAuditPort.Actions.ORDER_CREATED.equals(action) && data != null
                && "ONLINE".equals(data.get("channel"))) {
            return "OrderPlaced";
        }
        return eventTypeOf(action);
    }

    /** Đơn POS tạo ra là đơn đã hoàn tất (OrderCompleted); các hành động khác đổi sang PascalCase. */
    static String eventTypeOf(String action) {
        if (SalesAuditPort.Actions.ORDER_CREATED.equals(action)) {
            return "OrderCompleted";
        }
        StringBuilder sb = new StringBuilder();
        for (String part : action.split("_")) {
            if (!part.isEmpty()) {
                sb.append(part.charAt(0)).append(part.substring(1).toLowerCase(java.util.Locale.ROOT));
            }
        }
        return sb.toString();
    }
}
