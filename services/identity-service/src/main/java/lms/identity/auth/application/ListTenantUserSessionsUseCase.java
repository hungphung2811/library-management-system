package lms.identity.auth.application;

import lms.identity.auth.domain.RefreshToken;
import lms.identity.auth.domain.RefreshTokenRepository;
import lms.identity.auth.domain.UserSessionsViewedEvent;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Phiên đang đăng nhập của một người dùng TRONG một tenant (quy tắc ai được xem do context user kiểm trước khi gọi).
 * Truy vấn lọc user_id + tenant_id ngay trong SQL (refresh_tokens không có RLS): phiên nền tảng và phiên ở tenant khác
 * không bao giờ xuất hiện. Nếu {@code auditActorOrNull} khác null (xem phiên của NGƯỜI KHÁC) thì phát
 * {@link UserSessionsViewedEvent} (không chứa IP/thiết bị); tự xem thì truyền null. Kafka best-effort (ADR-0001).
 */
public class ListTenantUserSessionsUseCase {

    private final RefreshTokenRepository tokens;
    private final PublishAuthEventPort events;
    private final Clock clock;

    public ListTenantUserSessionsUseCase(RefreshTokenRepository tokens, PublishAuthEventPort events, Clock clock) {
        this.tokens = tokens;
        this.events = events;
        this.clock = clock;
    }

    public List<TenantUserSession> execute(UUID userId, UUID tenantId, UUID auditActorOrNull) {
        if (tenantId == null) throw new IllegalArgumentException("tenantId bắt buộc");
        List<RefreshToken> active = tokens.findActiveByUserIdAndTenantId(userId, tenantId, clock.instant());
        List<TenantUserSession> result = active.stream()
                .map(t -> new TenantUserSession(t.sessionId(), t.channel().name(), t.deviceSummary(), t.clientIp(),
                        t.sessionStartedAt(), t.createdAt(), t.expiresAt()))
                .sorted(Comparator.comparing(TenantUserSession::lastUsedAt).reversed())
                .toList();
        if (auditActorOrNull != null) {
            events.publish(new UserSessionsViewedEvent(UUID.randomUUID(), auditActorOrNull, userId, tenantId,
                    result.size(), clock.instant()));
        }
        return result;
    }
}
