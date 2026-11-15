package lms.identity.auth.application;

import lms.identity.auth.application.AuthDtos.TokenResponse;
import lms.identity.auth.domain.AccountDisabledException;
import lms.identity.auth.domain.RefreshToken;
import lms.identity.auth.domain.UserCredential;
import lms.identity.tenant.application.TenantChoice;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Phát hành cặp access token + refresh token theo tenant; dùng chung cho đăng nhập, đăng nhập ngoài, đổi tenant và
 * làm mới phiên. Token mang tenantId của tenant đã chọn và platformAdmin khi tài khoản có cờ; refresh token gắn cùng
 * tenant đó (hoặc là phiên nền tảng khi platform admin không có tenant).
 */
public class TokenService {

    private final UserAccessPort userAccessPort;
    private final TokenIssuerPort tokenIssuer;
    private final RefreshTokenCodecPort codec;
    private final SaveRefreshTokenPort refreshTokens;
    private final Clock clock;
    private final Duration refreshTokenTtl;
    private final UserDirectoryPort userDirectory;
    private final TenantAccessPort tenantAccess;

    public TokenService(UserAccessPort userAccessPort, TokenIssuerPort tokenIssuer, RefreshTokenCodecPort codec,
                        SaveRefreshTokenPort refreshTokens, Clock clock, Duration refreshTokenTtl,
                        TenantAccessPort tenantAccess) {
        this(userAccessPort, tokenIssuer, codec, refreshTokens, clock, refreshTokenTtl, userId -> Optional.empty(),
                tenantAccess);
    }

    public TokenService(UserAccessPort userAccessPort, TokenIssuerPort tokenIssuer, RefreshTokenCodecPort codec,
                        SaveRefreshTokenPort refreshTokens, Clock clock, Duration refreshTokenTtl,
                        UserDirectoryPort userDirectory, TenantAccessPort tenantAccess) {
        this.userAccessPort = userAccessPort;
        this.tokenIssuer = tokenIssuer;
        this.codec = codec;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
        this.refreshTokenTtl = refreshTokenTtl;
        this.userDirectory = userDirectory;
        this.tenantAccess = tenantAccess;
    }

    /** Đăng nhập không chỉ rõ tenant: chọn theo tenant dùng gần nhất. */
    public TokenResponse issueFor(UserCredential credential) {
        return issueFor(credential, null);
    }

    /** Đăng nhập bằng mật khẩu, tenantCodeOrNull: mã tenant người dùng muốn vào (tùy chọn). */
    public TokenResponse issueFor(UserCredential credential, String tenantCodeOrNull) {
        return issue(credential, credential.mustChangePassword(),
                () -> tenantAccess.selectForLogin(credential.userId(), tenantCodeOrNull, credential.platformAdmin()));
    }

    /**
     * Đăng nhập ngoài không dùng mật khẩu nội bộ nên không bị ràng buộc "phải đổi mật khẩu" (mật khẩu tạm có thể
     * người dùng chưa từng biết). Tenant chọn như đăng nhập không có mã.
     */
    public TokenResponse issueForExternal(UserCredential credential) {
        return issue(credential, false,
                () -> tenantAccess.selectForLogin(credential.userId(), null, credential.platformAdmin()));
    }

    /** Tenant đã được kiểm tra trước (đổi tenant, làm mới phiên); choice.tenantId null = phiên nền tảng. */
    public TokenResponse issueForTenant(UserCredential credential, TenantChoice choice) {
        return issue(credential, credential.mustChangePassword(), () -> choice);
    }

    private TokenResponse issue(UserCredential credential, boolean mustChange, Supplier<TenantChoice> tenantChoice) {
        // Người dùng bị vô hiệu hóa ở context user thì không được cấp token mới (cả đăng nhập, làm mới và đăng nhập ngoài)
        Optional<UserDirectoryPort.UserInfo> directory = userDirectory.find(credential.userId());
        if (directory.isPresent() && directory.get().disabled()) {
            throw new AccountDisabledException();
        }
        // Chọn tenant sau khi biết tài khoản còn dùng được; không có tenant nào thì từ chối ở đây (trước khi phát hành)
        TenantChoice choice = tenantChoice.get();
        UUID tenantId = choice.tenantId();
        if (tenantId == null && !credential.platformAdmin()) {
            throw new IllegalStateException("Chỉ platform admin mới có phiên không thuộc tenant nào");
        }
        // Phòng ban hiện tại lấy từ hồ sơ người dùng (đổi phòng ban có hiệu lực ở lần cấp token kế tiếp)
        UUID departmentId = directory.map(UserDirectoryPort.UserInfo::departmentId)
                .orElse(credential.departmentId());
        // 4a: role/permission còn toàn cục nên permissions và admin tính như trước, chưa lọc theo tenant
        UserAccessPort.UserAccess access = userAccessPort.fetch(credential.userId());
        // Phải đổi mật khẩu trước: token không mang permission nào nên mọi endpoint kiểm quyền (ở mọi service) đều
        // từ chối, chỉ còn các endpoint chỉ cần đăng nhập, như đổi mật khẩu. Vẫn mang tenantId.
        TokenIssuerPort.IssuedToken accessToken = tokenIssuer.issue(new TokenIssuerPort.Claims(
                credential.userId(), credential.username(), departmentId,
                access.roles(), mustChange ? List.of() : access.permissions(), mustChange,
                !mustChange && access.admin(), tenantId, !mustChange && credential.platformAdmin()));

        String rawRefresh = codec.generate();
        String hash = codec.hash(rawRefresh);
        RefreshToken refresh = tenantId != null
                ? RefreshToken.issueForTenant(credential.userId(), tenantId, hash, clock.instant(), refreshTokenTtl)
                : RefreshToken.issueForPlatform(credential.userId(), hash, clock.instant(), refreshTokenTtl);
        refreshTokens.save(refresh);
        return TokenResponse.bearer(accessToken.value(), rawRefresh, accessToken.expiresInSeconds(), mustChange,
                tenantId, choice.available());
    }
}
