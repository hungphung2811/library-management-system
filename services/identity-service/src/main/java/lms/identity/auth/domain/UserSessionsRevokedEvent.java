package lms.identity.auth.domain;

import lms.common.kernel.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Quản trị viên thu hồi phiên của người khác trong tenant (một phiên: sessionId khác null; tất cả: sessionId null).
 * Không chứa IP, user-agent hay tên thiết bị. Consumer: Audit.
 */
public record UserSessionsRevokedEvent(UUID eventId, UUID targetUserId, UUID revokedBy, UUID tenantId, UUID sessionId,
                                       int revokedCount, Instant occurredAt) implements DomainEvent {

    @Override
    public String aggregateId() {
        return targetUserId.toString();
    }
}
