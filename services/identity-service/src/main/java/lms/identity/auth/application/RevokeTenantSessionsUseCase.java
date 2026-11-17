package lms.identity.auth.application;

import lms.identity.auth.domain.UserSessionsRevokedEvent;
import java.time.Clock;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thu hồi mọi phiên (refresh token) của người dùng trong tenant được chỉ định; phiên ở tenant khác và phiên nền tảng
 * giữ nguyên. Access token đã phát vẫn dùng được đến khi hết hạn (TTL access token, mặc định 15 phút) vì JWT không
 * kiểm tra lại với kho; chỉ làm mới (refresh) bị chặn ngay. Quy tắc ai được thu hồi phiên của ai do context user kiểm.
 * Khi biết người thao tác (actorId) thì phát {@link UserSessionsRevokedEvent} để audit (Kafka best-effort).
 */
public class RevokeTenantSessionsUseCase {

    private final TenantSessionRevokerPort revoker;
    private final PublishAuthEventPort events;
    private final Clock clock;

    public RevokeTenantSessionsUseCase(TenantSessionRevokerPort revoker) {
        this(revoker, null, Clock.systemUTC());
    }

    public RevokeTenantSessionsUseCase(TenantSessionRevokerPort revoker, PublishAuthEventPort events, Clock clock) {
        this.revoker = revoker;
        this.events = events;
        this.clock = clock;
    }

    /** @return số refresh token đã thu hồi */
    @Transactional
    public int execute(UUID userId, UUID tenantId) {
        return revoker.revokeAllInTenant(userId, tenantId);
    }

    /** Như trên, kèm audit người thao tác. */
    @Transactional
    public int execute(UUID userId, UUID tenantId, UUID actorId) {
        int revoked = revoker.revokeAllInTenant(userId, tenantId);
        if (actorId != null && events != null) {
            events.publish(new UserSessionsRevokedEvent(UUID.randomUUID(), userId, actorId, tenantId, null, revoked,
                    clock.instant()));
        }
        return revoked;
    }
}
