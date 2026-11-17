package lms.identity.auth.application;

import lms.identity.auth.application.RefreshTokenCodecPort;
import lms.identity.auth.application.SaveRefreshTokenPort;
import lms.identity.auth.domain.RefreshTokenRepository;
import lms.identity.auth.domain.RevokeReason;
import java.time.Clock;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đăng xuất: thu hồi cả họ phiên của refresh token gửi lên (LOGOUT; đầu họ là token duy nhất còn hoạt động). Idempotent:
 * token không tồn tại hoặc đã thu hồi thì bỏ qua, không thu hồi gì thêm.
 */
public class LogoutUseCase {

    private final RefreshTokenRepository repository;
    private final SaveRefreshTokenPort savePort;
    private final RefreshTokenCodecPort codec;
    private final Clock clock;

    public LogoutUseCase(RefreshTokenRepository repository, SaveRefreshTokenPort savePort, RefreshTokenCodecPort codec) {
        this(repository, savePort, codec, Clock.systemUTC());
    }

    public LogoutUseCase(RefreshTokenRepository repository, SaveRefreshTokenPort savePort, RefreshTokenCodecPort codec,
                         Clock clock) {
        this.repository = repository;
        this.savePort = savePort;
        this.codec = codec;
        this.clock = clock;
    }

    @Transactional
    public void execute(String rawRefreshToken) {
        repository.findByTokenHash(codec.hash(rawRefreshToken)).ifPresent(token -> {
            if (!token.isRevoked()) {
                savePort.revokeSession(token.sessionId(), RevokeReason.LOGOUT, clock.instant());
            }
        });
    }
}
