package lms.identity.accesscontrol.domain;

import lms.common.kernel.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** Consumer: Audit. */
public record RoleAssignedEvent(UUID eventId, UUID userId, UUID roleId, String roleName, Instant occurredAt)
        implements DomainEvent {

    @Override
    public String aggregateId() {
        return userId.toString();
    }
}
