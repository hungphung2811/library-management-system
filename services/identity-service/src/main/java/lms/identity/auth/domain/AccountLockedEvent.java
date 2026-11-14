package lms.identity.auth.domain;

import lms.common.kernel.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** Consumer: Audit, Notification. */
public record AccountLockedEvent(UUID eventId, UUID userId, String username, Instant lockedUntil, Instant occurredAt)
        implements DomainEvent {

    @Override
    public String aggregateId() {
        return userId.toString();
    }
}
