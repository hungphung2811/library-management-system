package lms.identity.auth.application;

import lms.identity.auth.application.AuthDtos.TokenResponse;
import lms.identity.auth.application.RefreshTokenCodecPort;
import lms.identity.auth.application.SaveRefreshTokenPort;
import lms.identity.auth.domain.InvalidRefreshTokenException;
import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.RefreshToken;
import lms.identity.auth.domain.RefreshTokenRepository;
import lms.identity.auth.domain.UserCredentialRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.transaction.annotation.Transactional;

/** Làm mới phiên có rotation: mỗi refresh token chỉ dùng một lần; dùng lại token cũ thì thu hồi mọi phiên. */
public class RefreshTokenUseCase {

    private final RefreshTokenRepository refreshRepository;
    private final SaveRefreshTokenPort refreshSavePort;
    private final UserCredentialRepository credentialRepository;
    private final RefreshTokenCodecPort codec;
    private final TokenService tokenService;
    private final Clock clock;

    public RefreshTokenUseCase(RefreshTokenRepository refreshRepository, SaveRefreshTokenPort refreshSavePort,
                               UserCredentialRepository credentialRepository, RefreshTokenCodecPort codec,
                               TokenService tokenService, Clock clock) {
        this.refreshRepository = refreshRepository;
        this.refreshSavePort = refreshSavePort;
        this.credentialRepository = credentialRepository;
        this.codec = codec;
        this.tokenService = tokenService;
        this.clock = clock;
    }

    /** noRollbackFor: việc thu hồi hàng loạt khi phát hiện dùng lại token phải được lưu. */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public TokenResponse execute(String rawRefreshToken) {
        Instant now = clock.instant();
        RefreshToken token = refreshRepository.findByTokenHash(codec.hash(rawRefreshToken))
                .orElseThrow(() -> new InvalidRefreshTokenException("Refresh token không hợp lệ"));

        if (token.isRevoked()) {
            refreshSavePort.revokeAllByUserId(token.userId());
            throw new InvalidRefreshTokenException("Refresh token đã được dùng, mọi phiên của người dùng bị thu hồi");
        }
        token.ensureUsable(now);

        UserCredential credential = credentialRepository.findByUserId(token.userId())
                .orElseThrow(() -> new InvalidRefreshTokenException("Tài khoản không còn tồn tại"));
        credential.ensureCanLogin(now);

        token.revoke();
        refreshSavePort.save(token);
        return tokenService.issueFor(credential);
    }
}
