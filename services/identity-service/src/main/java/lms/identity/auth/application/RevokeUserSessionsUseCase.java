package lms.identity.auth.application;

import lms.identity.auth.application.SaveRefreshTokenPort;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thu hồi mọi phiên của người dùng, được kích hoạt khi access-control thu hồi role (RoleRevoked).
 * Idempotent nên an toàn khi consumer nhận lại cùng một event.
 */
public class RevokeUserSessionsUseCase {

    private final SaveRefreshTokenPort savePort;

    public RevokeUserSessionsUseCase(SaveRefreshTokenPort savePort) {
        this.savePort = savePort;
    }

    @Transactional
    public void execute(UUID userId) {
        savePort.revokeAllByUserId(userId);
    }
}
