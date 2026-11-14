package lms.identity.auth.application;

import lms.identity.auth.application.AuthDtos.TokenResponse;
import lms.identity.auth.application.RefreshTokenCodecPort;
import lms.identity.auth.application.SaveRefreshTokenPort;
import lms.identity.auth.application.TokenIssuerPort;
import lms.identity.auth.application.UserAccessPort;
import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.RefreshToken;
import java.time.Clock;
import java.time.Duration;

/** Phát hành cặp access token + refresh token; dùng chung cho đăng nhập và làm mới phiên. */
public class TokenService {

    private final UserAccessPort userAccessPort;
    private final TokenIssuerPort tokenIssuer;
    private final RefreshTokenCodecPort codec;
    private final SaveRefreshTokenPort refreshTokens;
    private final Clock clock;
    private final Duration refreshTokenTtl;

    public TokenService(UserAccessPort userAccessPort, TokenIssuerPort tokenIssuer, RefreshTokenCodecPort codec,
                        SaveRefreshTokenPort refreshTokens, Clock clock, Duration refreshTokenTtl) {
        this.userAccessPort = userAccessPort;
        this.tokenIssuer = tokenIssuer;
        this.codec = codec;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
        this.refreshTokenTtl = refreshTokenTtl;
    }

    public TokenResponse issueFor(UserCredential credential) {
        return issue(credential, credential.mustChangePassword());
    }

    /**
     * Đăng nhập ngoài không dùng mật khẩu nội bộ nên không bị ràng buộc "phải đổi mật khẩu" (mật khẩu tạm có thể
     * người dùng chưa từng biết).
     */
    public TokenResponse issueForExternal(UserCredential credential) {
        return issue(credential, false);
    }

    private TokenResponse issue(UserCredential credential, boolean mustChange) {
        UserAccessPort.UserAccess access = userAccessPort.fetch(credential.userId());
        // Phải đổi mật khẩu trước: token không mang permission nào nên mọi endpoint kiểm quyền (ở mọi service) đều
        // từ chối, chỉ còn các endpoint chỉ cần đăng nhập, như đổi mật khẩu.
        TokenIssuerPort.IssuedToken accessToken = tokenIssuer.issue(new TokenIssuerPort.Claims(
                credential.userId(), credential.username(), credential.departmentId(),
                access.roles(), mustChange ? java.util.List.of() : access.permissions(), mustChange,
                !mustChange && access.admin()));

        String rawRefresh = codec.generate();
        refreshTokens.save(RefreshToken.issue(credential.userId(), codec.hash(rawRefresh), clock.instant(), refreshTokenTtl));
        return TokenResponse.bearer(accessToken.value(), rawRefresh, accessToken.expiresInSeconds(), mustChange);
    }
}
