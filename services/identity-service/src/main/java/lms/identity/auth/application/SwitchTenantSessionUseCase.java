package lms.identity.auth.application;

import lms.identity.auth.application.AuthDtos.TokenResponse;
import lms.identity.auth.domain.InvalidCredentialsException;
import lms.identity.auth.domain.InvalidRefreshTokenException;
import lms.identity.auth.domain.RefreshToken;
import lms.identity.auth.domain.TokenChannel;
import lms.identity.auth.domain.RefreshTokenRepository;
import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.UserCredentialRepository;
import lms.identity.tenant.application.TenantChoice;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * POST /internal/auth/switch-tenant: người đang đăng nhập đổi sang tenant khác. Context tenant kiểm tra membership
 * ACTIVE + tenant ACTIVE và ghi tenant dùng gần nhất; ở đây cấp cặp access + refresh token mới cho tenant đó và
 * thu hồi refresh token hiện tại (request mang kèm). Refresh token phải thuộc đúng người dùng và còn dùng được, nếu
 * không thì 401 như refresh token không hợp lệ. Chỉ thu hồi sau khi tenant đích được chấp nhận (bị 403 thì token cũ
 * giữ nguyên); các refresh token khác của người dùng không bị thu hồi.
 *
 * <p>Không có transaction bao ngoài (R20): các bước đọc/kiểm tra tự có transaction ngắn; thu hồi token hiện tại và lưu
 * token mới nằm trong cùng một giao dịch ở bước cuối của TokenService.
 */
public class SwitchTenantSessionUseCase {

    private final UserCredentialRepository credentials;
    private final RefreshTokenRepository refreshRepository;
    private final SaveRefreshTokenPort refreshSavePort;
    private final RefreshTokenCodecPort codec;
    private final TenantAccessPort tenantAccess;
    private final TokenService tokenService;
    private final Clock clock;

    public SwitchTenantSessionUseCase(UserCredentialRepository credentials, RefreshTokenRepository refreshRepository,
                                      SaveRefreshTokenPort refreshSavePort, RefreshTokenCodecPort codec,
                                      TenantAccessPort tenantAccess, TokenService tokenService, Clock clock) {
        this.credentials = credentials;
        this.refreshRepository = refreshRepository;
        this.refreshSavePort = refreshSavePort;
        this.codec = codec;
        this.tenantAccess = tenantAccess;
        this.tokenService = tokenService;
        this.clock = clock;
    }

    public TokenResponse execute(UUID userId, UUID tenantId, String rawRefreshToken) {
        return execute(userId, tenantId, rawRefreshToken, null);
    }

    /** Đổi tenant giữ họ phiên (cùng thiết bị); client cập nhật nhãn thiết bị (null = giữ nhãn cũ). */
    public TokenResponse execute(UUID userId, UUID tenantId, String rawRefreshToken, ClientInfo client) {
        Instant now = clock.instant();
        RefreshToken current = refreshRepository.findByTokenHash(codec.hash(rawRefreshToken))
                .filter(t -> t.userId().equals(userId))
                .orElseThrow(() -> new InvalidRefreshTokenException("Refresh token không hợp lệ"));
        current.ensureUsable(now);
        // Đổi tenant chỉ dành cho phiên nhân viên: phiên storefront gắn với đúng một tenant (cửa hàng)
        if (current.channel() != TokenChannel.STAFF) {
            throw new InvalidRefreshTokenException("Refresh token không hợp lệ");
        }

        UserCredential credential = credentials.findByUserId(userId).orElseThrow(InvalidCredentialsException::new);
        credential.ensureCanLogin(now);
        TenantChoice choice = tenantAccess.switchTo(userId, tenantId); // ném NoTenantAccessException, token cũ giữ nguyên

        return tokenService.issueForTenant(credential, choice, current, client);
    }
}
