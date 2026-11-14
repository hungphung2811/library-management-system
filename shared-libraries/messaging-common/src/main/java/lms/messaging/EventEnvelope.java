package lms.messaging;

import lms.common.kernel.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** Envelope chuẩn: eventId, eventType, aggregateId, occurredAt, version + payload. */
public record EventEnvelope(UUID eventId, String eventType, String aggregateId, Instant occurredAt,
                            int version, Object payload) {

    public static EventEnvelope of(DomainEvent event) {
        return new EventEnvelope(event.eventId(), event.eventType(), event.aggregateId(),
                event.occurredAt(), event.version(), event);
    }
}
