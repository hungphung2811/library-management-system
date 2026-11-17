package lms.identity.auth.application;

import lms.identity.auth.domain.SessionNotFoundException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Người dùng đăng xuất MỘT phiên của chính mình (kể cả phiên hiện tại, tương đương logout). Câu cập nhật lọc
 * user_id + session_id nên không thu hồi được phiên của người khác; không có/không phải của mình/đã kết thúc đều là
 * một mã chung SESSION_NOT_FOUND. Chỉ chặn refresh: access token đã phát còn dùng được tối đa đến hết TTL.
 */
public class RevokeMySessionUseCase {

    private final SaveRefreshTokenPort port;
    private final Clock clock;

    public RevokeMySessionUseCase(SaveRefreshTokenPort port, Clock clock) {
        this.port = port;
        this.clock = clock;
    }

    @Transactional
    public void execute(UUID userId, UUID sessionId) {
        if (port.revokeSessionOfUser(userId, sessionId, clock.instant()) == 0) {
            throw new SessionNotFoundException();
        }
    }
}
