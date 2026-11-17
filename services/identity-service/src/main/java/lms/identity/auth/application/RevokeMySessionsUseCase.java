package lms.identity.auth.application;

import lms.identity.auth.domain.SessionNotIdentifiedException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Người dùng đăng xuất nhiều phiên của chính mình: keepCurrent=true thu hồi mọi phiên trừ phiên hiện tại (claim sid;
 * thiếu sid thì 409 SESSION_NOT_IDENTIFIED), false thu hồi tất cả kể cả hiện tại. Phạm vi: mọi tenant, mọi kênh.
 */
public class RevokeMySessionsUseCase {

    private final SaveRefreshTokenPort port;
    private final Clock clock;

    public RevokeMySessionsUseCase(SaveRefreshTokenPort port, Clock clock) {
        this.port = port;
        this.clock = clock;
    }

    /** @return số phiên đã thu hồi */
    @Transactional
    public int execute(UUID userId, UUID currentSessionId, boolean keepCurrent) {
        if (keepCurrent && currentSessionId == null) throw new SessionNotIdentifiedException();
        return port.revokeAllExcept(userId, keepCurrent ? currentSessionId : null, clock.instant());
    }
}
