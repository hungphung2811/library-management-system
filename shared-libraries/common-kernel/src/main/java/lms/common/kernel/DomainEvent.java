package lms.common.kernel;

import java.time.Instant;
import java.util.UUID;

/** Contract chung cho mọi domain event: eventId, eventType, aggregateId, occurredAt, version. */
public interface DomainEvent {

    UUID eventId();

    String aggregateId();

    Instant occurredAt();

    default String eventType() {
        return getClass().getSimpleName();
    }

    default int version() {
        return 1;
    }
}
