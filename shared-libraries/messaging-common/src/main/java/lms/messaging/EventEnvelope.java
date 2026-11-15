package lms.messaging;

import lms.common.kernel.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Envelope chuẩn: eventId, eventType, aggregateId, occurredAt, version + payload + tenantId.
 * {@code tenantId} nullable: event cũ (trước khi đa tenant) không có trường này và được đọc thành null.
 */
public record EventEnvelope(UUID eventId, String eventType, String aggregateId, Instant occurredAt,
                            int version, Object payload, UUID tenantId) {

    /** Hình dạng cũ (không có tenantId), giữ để code gọi cũ vẫn biên dịch. */
    public EventEnvelope(UUID eventId, String eventType, String aggregateId, Instant occurredAt,
                         int version, Object payload) {
        this(eventId, eventType, aggregateId, occurredAt, version, payload, null);
    }

    public static EventEnvelope of(DomainEvent event) {
        return of(event, null);
    }

    public static EventEnvelope of(DomainEvent event, UUID tenantId) {
        return new EventEnvelope(event.eventId(), event.eventType(), event.aggregateId(),
                event.occurredAt(), event.version(), event, tenantId);
    }
}
