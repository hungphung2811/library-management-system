package lms.identity.auth.domain;

import lms.common.kernel.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** Consumer: Audit (Login History). */
public record LoginSucceededEvent(UUID eventId, UUID userId, String username, Instant occurredAt) implements DomainEvent {

    @Override
    public String aggregateId() {
        return userId.toString();
    }
}
