package lms.identity.auth.application;

import lms.identity.auth.application.AuthDtos.TokenResponse;
import lms.identity.auth.domain.InvalidRefreshTokenException;
import lms.identity.auth.domain.RefreshTokenSupersededException;
import lms.identity.auth.domain.RevokeReason;
import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.RefreshToken;
import lms.identity.auth.domain.TokenChannel;
import lms.identity.auth.domain.RefreshTokenRepository;
import lms.identity.auth.domain.UserCredentialRepository;
import lms.identity.tenant.application.TenantChoice;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Làm mới phiên có rotation: mỗi refresh token chỉ dùng một lần. Dùng lại token đã thu hồi (ADR-0017): trong cửa sổ ân
 * hạn sau lần xoay (race nhiều tab) thì 401 REFRESH_TOKEN_SUPERSEDED, không phát token và không thu hồi gì; ngoài cửa sổ
 * thì chỉ thu hồi đúng họ phiên của token đó (không đụng thiết bị khác). Phiên giữ
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
    private final Duration reuseGrace;

    /** Cửa sổ ân hạn mặc định (auth.refresh-reuse-grace). */
    public static final Duration DEFAULT_REUSE_GRACE = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenUseCase.class);

    public RefreshTokenUseCase(RefreshTokenRepository refreshRepository, SaveRefreshTokenPort refreshSavePort,
                               UserCredentialRepository credentialRepository, RefreshTokenCodecPort codec,
                               TokenService tokenService, TenantAccessPort tenantAccess, Clock clock) {
        this(refreshRepository, refreshSavePort, credentialRepository, codec, tokenService, tenantAccess, clock,
                DEFAULT_REUSE_GRACE);
    }

    /** reuseGrace: ân hạn sau lần xoay mà token cũ dùng lại chỉ bị từ chối mềm; PT0S = tắt (luôn thu hồi họ). */
    public RefreshTokenUseCase(RefreshTokenRepository refreshRepository, SaveRefreshTokenPort refreshSavePort,
                               UserCredentialRepository credentialRepository, RefreshTokenCodecPort codec,
                               TokenService tokenService, TenantAccessPort tenantAccess, Clock clock,
                               Duration reuseGrace) {
        this.reuseGrace = reuseGrace == null ? Duration.ZERO : reuseGrace;
        this.refreshRepository = refreshRepository;
        this.refreshSavePort = refreshSavePort;
        this.credentialRepository = credentialRepository;
        this.codec = codec;
        this.tokenService = tokenService;
        this.tenantAccess = tenantAccess;
        this.clock = clock;
    }

    public TokenResponse execute(String rawRefreshToken) {
        return execute(rawRefreshToken, TokenChannel.STAFF);
    }

    /**
     * Làm mới theo kênh người gọi (A18): refresh token chỉ làm mới được ở đúng kênh đã phát hành (STAFF qua lms-api,
     * STOREFRONT qua customer-api); lệch kênh thì 401 như token không hợp lệ và token KHÔNG bị thu hồi/xoay vòng (để
     * kênh đúng vẫn dùng được). Phiên mới giữ kênh của token gốc.
     */
    public TokenResponse execute(String rawRefreshToken, TokenChannel channel) {
        return execute(rawRefreshToken, channel, null);
    }

    /**
     * Như trên, thêm kiểm tenant (ADR-0013 2.4): {@code expectedTenantId} khác null mà khác {@code refresh_tokens.tenant_id}
     * (kể cả token phiên nền tảng có tenant NULL) thì 401 như token không hợp lệ. Kiểm SAU bước phát hiện dùng lại token đã
     * thu hồi (giữ nguyên bảo vệ đó) và TRƯỚC khi xoay vòng: token đúng nhưng gọi nhầm tenant không bị thu hồi.
     */
    public TokenResponse execute(String rawRefreshToken, TokenChannel channel, java.util.UUID expectedTenantId) {
        return execute(rawRefreshToken, channel, expectedTenantId, null);
    }

    /** Như trên, thêm thông tin client (IP, user-agent) để cập nhật nhãn thiết bị của phiên (null = giữ nhãn cũ). */
    public TokenResponse execute(String rawRefreshToken, TokenChannel channel, java.util.UUID expectedTenantId,
                                 ClientInfo client) {
        TokenChannel expected = channel == null ? TokenChannel.STAFF : channel;
        Instant now = clock.instant();
        RefreshToken token = refreshRepository.findByTokenHash(codec.hash(rawRefreshToken))
                .orElseThrow(() -> new InvalidRefreshTokenException("Refresh token không hợp lệ"));

        if (token.isRevoked()) {
            if (token.isRecentlyRotated(now, reuseGrace)) {
                throw new RefreshTokenSupersededException();
            }
            // Dùng lại token đã thu hồi ngoài ân hạn: chỉ thu hồi họ phiên này (không log token, IP, user-agent)
            refreshSavePort.revokeSession(token.sessionId(), RevokeReason.REUSE_DETECTED, now);
            log.warn("Phát hiện dùng lại refresh token đã thu hồi, thu hồi phiên: userId={}, sessionId={}",
                    token.userId(), token.sessionId());
            throw new InvalidRefreshTokenException("Refresh token đã được dùng, phiên này bị thu hồi");
        }
        token.ensureUsable(now);
        if (token.channel() != expected) {
            throw new InvalidRefreshTokenException("Refresh token không hợp lệ");
        }
        if (expectedTenantId != null && !expectedTenantId.equals(token.tenantId())) {
            throw new InvalidRefreshTokenException("Refresh token không hợp lệ");
        }

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

        return tokenService.issueForTenant(credential, choice, token, client);
    }
}
