package lms.identity.auth.application;

import lms.identity.auth.application.RefreshTokenCodecPort;
import lms.identity.auth.application.SaveRefreshTokenPort;
import lms.identity.auth.domain.RefreshTokenRepository;
import org.springframework.transaction.annotation.Transactional;

/** Đăng xuất: thu hồi refresh token. Idempotent, token không tồn tại hoặc đã thu hồi thì bỏ qua. */
public class LogoutUseCase {

    private final RefreshTokenRepository repository;
    private final SaveRefreshTokenPort savePort;
    private final RefreshTokenCodecPort codec;

    public LogoutUseCase(RefreshTokenRepository repository, SaveRefreshTokenPort savePort, RefreshTokenCodecPort codec) {
        this.repository = repository;
        this.savePort = savePort;
        this.codec = codec;
    }

    @Transactional
    public void execute(String rawRefreshToken) {
        repository.findByTokenHash(codec.hash(rawRefreshToken)).ifPresent(token -> {
            if (!token.isRevoked()) {
                token.revoke();
                savePort.save(token);
            }
        });
    }
}
