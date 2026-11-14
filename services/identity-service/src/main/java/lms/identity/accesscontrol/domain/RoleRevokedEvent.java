package lms.identity.accesscontrol.domain;

import lms.common.kernel.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** Consumer: Auth (thu hồi refresh token để quyền mới có hiệu lực), Audit. */
public record RoleRevokedEvent(UUID eventId, UUID userId, UUID roleId, String roleName, Instant occurredAt)
        implements DomainEvent {

    @Override
    public String aggregateId() {
        return userId.toString();
    }
}
