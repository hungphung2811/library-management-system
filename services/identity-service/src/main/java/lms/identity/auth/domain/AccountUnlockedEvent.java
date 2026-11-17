package lms.identity.auth.domain;

import lms.common.kernel.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** Quản trị viên mở khóa tài khoản bị khóa do đăng nhập sai. Consumer: Audit. */
public record AccountUnlockedEvent(UUID eventId, UUID userId, String username, UUID unlockedBy, Instant occurredAt)
        implements DomainEvent {

    @Override
    public String aggregateId() {
        return userId.toString();
    }
}
