package lms.identity.auth.application;

import lms.identity.auth.domain.SessionNotFoundException;
import lms.identity.auth.domain.UserSessionsRevokedEvent;
import java.time.Clock;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quản trị viên tenant thu hồi MỘT phiên của người dùng trong tenant. Một câu SQL lọc user_id + tenant_id + session_id:
 * 0 dòng thì SESSION_NOT_FOUND (một mã chung cho phiên không tồn tại, của người khác, ở tenant khác, phiên nền tảng,
 * đã kết thúc). Phát {@link UserSessionsRevokedEvent} (không chứa IP/thiết bị) khi actorId khác null.
 */
public class RevokeTenantUserSessionUseCase {

    private final TenantSessionRevokerPort revoker;
    private final PublishAuthEventPort events;
    private final Clock clock;

    public RevokeTenantUserSessionUseCase(TenantSessionRevokerPort revoker, PublishAuthEventPort events, Clock clock) {
        this.revoker = revoker;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void execute(UUID userId, UUID tenantId, UUID sessionId, UUID actorId) {
        if (tenantId == null) throw new SessionNotFoundException();
        if (revoker.revokeSessionInTenant(userId, tenantId, sessionId) == 0) throw new SessionNotFoundException();
        if (actorId != null) {
            events.publish(new UserSessionsRevokedEvent(UUID.randomUUID(), userId, actorId, tenantId, sessionId, 1,
                    clock.instant()));
        }
    }
}
