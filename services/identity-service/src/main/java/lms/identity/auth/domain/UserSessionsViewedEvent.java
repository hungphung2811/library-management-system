package lms.identity.auth.domain;

import lms.common.kernel.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Quản trị viên xem danh sách phiên của người khác (danh sách có IP đầy đủ nên việc xem được ghi audit). Không chứa IP,
 * user-agent hay tên thiết bị, chỉ số phiên. Người dùng tự xem phiên của mình không phát sự kiện. Consumer: Audit.
 */
public record UserSessionsViewedEvent(UUID eventId, UUID actorId, UUID targetUserId, UUID tenantId, int sessionCount,
                                      Instant occurredAt) implements DomainEvent {

    @Override
    public String aggregateId() {
        return targetUserId.toString();
    }
}
