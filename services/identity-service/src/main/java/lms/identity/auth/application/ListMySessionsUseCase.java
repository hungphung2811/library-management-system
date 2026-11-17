package lms.identity.auth.application;

import lms.identity.auth.domain.RefreshToken;
import lms.identity.auth.domain.RefreshTokenRepository;
import lms.identity.tenant.application.TenantSummary;
import java.time.Clock;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Liệt kê các phiên đang đăng nhập của CHÍNH người dùng: mọi tenant, phiên nền tảng, cả kênh STAFF và STOREFRONT
 * (tài khoản là toàn cục). Mỗi phiên còn sống có đúng một token hoạt động nên mỗi dòng là một phiên. Sắp theo lần dùng
 * gần nhất giảm dần. currentSessionId (claim sid) null thì không phiên nào được đánh dấu hiện tại.
 */
public class ListMySessionsUseCase {

    private final RefreshTokenRepository tokens;
    private final TenantAccessPort tenantAccess;
    private final Clock clock;

    public ListMySessionsUseCase(RefreshTokenRepository tokens, TenantAccessPort tenantAccess, Clock clock) {
        this.tokens = tokens;
        this.tenantAccess = tenantAccess;
        this.clock = clock;
    }

    public List<SessionView> execute(UUID userId, UUID currentSessionId) {
        List<RefreshToken> active = tokens.findActiveByUserId(userId, clock.instant());
        Map<UUID, String> names = new HashMap<>();
        if (active.stream().anyMatch(t -> t.tenantId() != null)) {
            for (TenantSummary t : tenantAccess.available(userId)) names.put(t.id(), t.name());
        }
        return active.stream()
                .map(t -> new SessionView(t.sessionId(), t.channel().name(), t.tenantId(),
                        t.tenantId() == null ? null : names.get(t.tenantId()), t.deviceSummary(), t.clientIp(),
                        t.sessionStartedAt(), t.createdAt(), t.expiresAt(), t.sessionId().equals(currentSessionId)))
                .sorted(Comparator.comparing(SessionView::lastUsedAt).reversed())
                .toList();
    }
}
