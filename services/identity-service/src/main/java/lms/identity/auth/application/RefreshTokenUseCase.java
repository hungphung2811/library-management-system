package lms.identity.auth.application;

import lms.identity.auth.application.AuthDtos.TokenResponse;
import lms.identity.auth.domain.InvalidRefreshTokenException;
import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.RefreshToken;
import lms.identity.auth.domain.RefreshTokenRepository;
import lms.identity.auth.domain.UserCredentialRepository;
import lms.identity.tenant.application.TenantChoice;
import java.time.Clock;
import java.time.Instant;

/**
 * Làm mới phiên có rotation: mỗi refresh token chỉ dùng một lần; dùng lại token cũ thì thu hồi mọi phiên. Phiên giữ
 * nguyên tenant của refresh token sau khi kiểm tra lại membership ACTIVE và tenant ACTIVE (không còn quyền thì 403
 * NO_TENANT_ACCESS, token không bị xoay vòng). Phiên nền tảng chỉ làm mới được khi tài khoản còn cờ platform_admin.
 * Refresh token không gắn tenant nào (phát hành trước khi có đa tenant) bị từ chối như token không hợp lệ.
 *
 * <p>Không có transaction bao ngoài (R20): phát hiện dùng lại thì thu hồi hàng loạt trong transaction riêng, đã commit
 * trước khi ném InvalidRefreshTokenException (thay cho noRollbackFor trước đây); xoay vòng (thu hồi token cũ + lưu token
 * mới) nằm trong cùng một giao dịch ở bước cuối của TokenService.
 */
public class RefreshTokenUseCase {

    private final RefreshTokenRepository refreshRepository;
    private final SaveRefreshTokenPort refreshSavePort;
    private final UserCredentialRepository credentialRepository;
    private final RefreshTokenCodecPort codec;
    private final TokenService tokenService;
    private final TenantAccessPort tenantAccess;
    private final Clock clock;

    public RefreshTokenUseCase(RefreshTokenRepository refreshRepository, SaveRefreshTokenPort refreshSavePort,
                               UserCredentialRepository credentialRepository, RefreshTokenCodecPort codec,
                               TokenService tokenService, TenantAccessPort tenantAccess, Clock clock) {
        this.refreshRepository = refreshRepository;
        this.refreshSavePort = refreshSavePort;
        this.credentialRepository = credentialRepository;
        this.codec = codec;
        this.tokenService = tokenService;
        this.tenantAccess = tenantAccess;
        this.clock = clock;
    }

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

        TenantChoice choice;
        if (token.tenantId() != null) {
            choice = tenantAccess.verify(token.userId(), token.tenantId()); // ném NoTenantAccessException
        } else if (token.platformSession() && credential.platformAdmin()) {
            choice = new TenantChoice(null, tenantAccess.available(token.userId()));
        } else {
            throw new InvalidRefreshTokenException("Refresh token không hợp lệ");
        }

        return tokenService.issueForTenant(credential, choice, token);
    }
}
