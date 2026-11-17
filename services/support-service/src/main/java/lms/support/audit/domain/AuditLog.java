package lms.support.audit.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Một bản ghi audit của một domain event. eventId là khóa idempotency: cùng một event giao lại nhiều lần
 * (Kafka at-least-once) chỉ tạo đúng một bản ghi.
 */
public record AuditLog(UUID id, UUID eventId, String eventType, String aggregateId, Instant occurredAt,
                       Instant recordedAt, String payload, UUID tenantId) {

    public AuditLog {
        if (id == null) throw new InvalidAuditEventException("id không được null");
        if (eventId == null) throw new InvalidAuditEventException("eventId không được null");
        if (eventType == null || eventType.isBlank()) throw new InvalidAuditEventException("eventType không được trống");
        if (aggregateId == null || aggregateId.isBlank()) throw new InvalidAuditEventException("aggregateId không được trống");
        if (occurredAt == null) throw new InvalidAuditEventException("occurredAt không được null");
        if (recordedAt == null) throw new InvalidAuditEventException("recordedAt không được null");
        if (payload == null) throw new InvalidAuditEventException("payload không được null");
    }

    public static AuditLog capture(UUID eventId, String eventType, String aggregateId, Instant occurredAt,
                                   String payload, Instant now) {
        // tenantId do TenantContext/@TenantId gán lúc ghi; chỉ có giá trị khi đọc lại từ DB
        return new AuditLog(UUID.randomUUID(), eventId, eventType, aggregateId, occurredAt, now, payload, null);
    }
}
