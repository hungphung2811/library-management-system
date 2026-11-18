package lms.identity.auth.application;

import lms.identity.auth.application.AuthDtos.TokenResponse;
import lms.identity.auth.domain.AccountDisabledException;
import lms.identity.auth.domain.InvalidCredentialsException;
import lms.identity.auth.domain.DeviceSummary;
import lms.identity.auth.domain.RefreshToken;
import lms.identity.auth.domain.RevokeReason;
import lms.identity.auth.domain.TokenChannel;
import lms.identity.auth.domain.UserCredential;
import lms.identity.tenant.application.TenantChoice;
import lms.identity.tenant.application.TenantSummary;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lms.security.JwtClaims;
import lms.tenant.TenantContext;

/**
 * Phát hành cặp access token + refresh token theo tenant; dùng chung cho đăng nhập, đăng nhập ngoài, đổi tenant và
 * làm mới phiên. Token mang tenantId của tenant đã chọn và platformAdmin khi tài khoản có cờ; refresh token gắn cùng
 * tenant đó (hoặc là phiên nền tảng khi platform admin không có tenant).
 *
 * <p>roles/permissions/admin chỉ lấy từ role của tenant đã chọn: việc đọc chạy trong
 * {@code TenantContext.callAs(<tenant đã chọn>, ...)} (role là dữ liệu thuộc tenant), bất kể tenant của request hiện tại
 * (đăng nhập không có tenant, đổi tenant đang ở tenant cũ). Phiên nền tảng (không tenant) không có role tenant nào.
 * Phòng ban trong token là phòng ban của membership ở tenant đã chọn.
 *
 * <p>Bất biến (R20): KHÔNG gọi TokenService bên trong một transaction đang mở. Mỗi bước đọc/ghi chạy transaction ngắn
 * của riêng nó (một kết nối tại một thời điểm), bước đọc quyền mở transaction sau khi đã đặt tenant đã chọn. Gọi trong
 * transaction ngoài sẽ giữ 2 kết nối cùng lúc mỗi lần cấp token (nguy cơ cạn pool khi đăng nhập dồn dập). Refresh
 * token cũ (nếu có) được thu hồi cùng giao dịch với việc lưu refresh token mới ({@link SaveRefreshTokenPort#rotate}).
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
    private final List<String> storefrontPermissions;
    private final TenantAccessModePort tenantAccessMode;

    /**
     * Allowlist mặc định của token kênh STOREFRONT (A19/A5 mở rộng): chỉ khóa "của tôi" của khách và tra cứu catalog.
     * Mục kết thúc bằng {@code /*} khớp mọi khóa cùng controller; mục khác khớp chính xác. Cấu hình bằng
     * {@code lms.identity.token.storefront-permissions}.
     */
    public static final List<String> DEFAULT_STOREFRONT_PERMISSIONS =
            List.of("ShopController/*", "TitleController/lookup", "ReservationController/mine",
                    "ReservationController/cancel");

    public TokenService(UserAccessPort userAccessPort, TokenIssuerPort tokenIssuer, RefreshTokenCodecPort codec,
                        SaveRefreshTokenPort refreshTokens, Clock clock, Duration refreshTokenTtl,
                        TenantAccessPort tenantAccess) {
        this(userAccessPort, tokenIssuer, codec, refreshTokens, clock, refreshTokenTtl, userId -> Optional.empty(),
                tenantAccess);
    }

    public TokenService(UserAccessPort userAccessPort, TokenIssuerPort tokenIssuer, RefreshTokenCodecPort codec,
                        SaveRefreshTokenPort refreshTokens, Clock clock, Duration refreshTokenTtl,
                        UserDirectoryPort userDirectory, TenantAccessPort tenantAccess) {
        this(userAccessPort, tokenIssuer, codec, refreshTokens, clock, refreshTokenTtl, userDirectory, tenantAccess,
                DEFAULT_STOREFRONT_PERMISSIONS);
    }

    public TokenService(UserAccessPort userAccessPort, TokenIssuerPort tokenIssuer, RefreshTokenCodecPort codec,
                        SaveRefreshTokenPort refreshTokens, Clock clock, Duration refreshTokenTtl,
                        UserDirectoryPort userDirectory, TenantAccessPort tenantAccess,
                        Collection<String> storefrontPermissions) {
        this(userAccessPort, tokenIssuer, codec, refreshTokens, clock, refreshTokenTtl, userDirectory, tenantAccess,
                storefrontPermissions, TenantAccessModePort.FULL_ALWAYS);
    }

    /**
     * tenantAccessMode (ADR-0031 D4): chế độ truy cập của tenant; {@code READ_ONLY} thì access token của phiên tenant
     * mang claim {@code tenantAccess = READ_ONLY} (không áp cho platform admin), {@code FULL} thì không ghi claim.
     */
    public TokenService(UserAccessPort userAccessPort, TokenIssuerPort tokenIssuer, RefreshTokenCodecPort codec,
                        SaveRefreshTokenPort refreshTokens, Clock clock, Duration refreshTokenTtl,
                        UserDirectoryPort userDirectory, TenantAccessPort tenantAccess,
                        Collection<String> storefrontPermissions, TenantAccessModePort tenantAccessMode) {
        this.tenantAccessMode = tenantAccessMode != null ? tenantAccessMode : TenantAccessModePort.FULL_ALWAYS;
        this.storefrontPermissions = storefrontPermissions == null ? DEFAULT_STOREFRONT_PERMISSIONS
                : storefrontPermissions.stream().map(String::trim).filter(x -> !x.isEmpty()).toList();
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
     * Đăng nhập mật khẩu theo kênh. Kênh STOREFRONT (customer-api): token mang {@code aud = lms-storefront} và KHÔNG
     * mang admin/platformAdmin dù tài khoản có role admin (nhân viên đăng nhập ở storefront nhận token hạn chế); phiên
     * storefront bắt buộc có tenant. Refresh token lưu kênh để làm mới giữ kênh.
     */
    public TokenResponse issueFor(UserCredential credential, String tenantCodeOrNull, TokenChannel channel) {
        return issueFor(credential, tenantCodeOrNull, channel, null);
    }

    /** Như trên, kèm thông tin client (IP, user-agent) để gắn nhãn thiết bị cho phiên mới (null = không rõ). */
    public TokenResponse issueFor(UserCredential credential, String tenantCodeOrNull, TokenChannel channel,
                                  ClientInfo client) {
        return issue(credential, credential.mustChangePassword(),
                () -> tenantAccess.selectForLogin(credential.userId(), tenantCodeOrNull, credential.platformAdmin()),
                null, channel, client);
    }

    /**
     * Đăng nhập ngoài không dùng mật khẩu nội bộ nên không bị ràng buộc "phải đổi mật khẩu" (mật khẩu tạm có thể
     * người dùng chưa từng biết). Tenant chọn như đăng nhập không có mã.
     */
    public TokenResponse issueForExternal(UserCredential credential) {
        return issueForExternal(credential, null);
    }

    public TokenResponse issueForExternal(UserCredential credential, ClientInfo client) {
        return issue(credential, false,
                () -> tenantAccess.selectForLogin(credential.userId(), null, credential.platformAdmin()), null,
                TokenChannel.STAFF, client);
    }

    /** Tenant đã được kiểm tra trước (đổi tenant, làm mới phiên); choice.tenantId null = phiên nền tảng. */
    public TokenResponse issueForTenant(UserCredential credential, TenantChoice choice) {
        return issueForTenant(credential, choice, null);
    }

    /**
     * Như {@link #issueForTenant(UserCredential, TenantChoice)}, đồng thời thu hồi refresh token hiện tại
     * (replacedOrNull) trong cùng giao dịch lưu refresh token mới. Lỗi trước bước lưu thì token hiện tại giữ nguyên.
     */
    public TokenResponse issueForTenant(UserCredential credential, TenantChoice choice, RefreshToken replacedOrNull) {
        return issueForTenant(credential, choice, replacedOrNull, null);
    }

    /**
     * Làm mới/đổi tenant: token mới giữ {@code sessionId} và thời điểm bắt đầu phiên của token cũ (cùng thiết bị). Nhãn
     * thiết bị và IP lấy từ {@code client} nếu có, không thì chép từ token cũ. Token cũ được xoay có điều kiện; nếu
     * request song song đã xoay trước thì ném {@code RefreshTokenSupersededException}.
     */
    public TokenResponse issueForTenant(UserCredential credential, TenantChoice choice, RefreshToken replacedOrNull,
                                        ClientInfo client) {
        // Làm mới giữ kênh của refresh token gốc (token cũ không có kênh là STAFF)
        TokenChannel channel = replacedOrNull == null ? TokenChannel.STAFF : replacedOrNull.channel();
        return issue(credential, credential.mustChangePassword(), () -> choice, replacedOrNull, channel, client);
    }

    private TokenResponse issue(UserCredential credential, boolean mustChange, Supplier<TenantChoice> tenantChoice) {
        return issue(credential, mustChange, tenantChoice, null, TokenChannel.STAFF, null);
    }

    private TokenResponse issue(UserCredential credential, boolean mustChange, Supplier<TenantChoice> tenantChoice,
                                RefreshToken replacedOrNull, TokenChannel channel, ClientInfo client) {
        // Người dùng bị vô hiệu hóa ở context user thì không được cấp token mới (cả đăng nhập, làm mới và đăng nhập ngoài)
        Optional<UserDirectoryPort.UserInfo> directory = userDirectory.find(credential.userId());
        if (directory.isPresent() && directory.get().disabled()) {
            throw new AccountDisabledException();
        }
        // Chọn tenant sau khi biết tài khoản còn dùng được; không có tenant nào thì từ chối ở đây (trước khi phát hành)
        TenantChoice choice = tenantChoice.get();
        UUID tenantId = choice.tenantId();
        boolean storefront = channel == TokenChannel.STOREFRONT;
        // Phiên storefront luôn thuộc một tenant cụ thể (platform admin không tenant không có phiên storefront)
        if (storefront && tenantId == null) {
            throw new InvalidCredentialsException();
        }
        if (tenantId == null && !credential.platformAdmin()) {
            throw new IllegalStateException("Chỉ platform admin mới có phiên không thuộc tenant nào");
        }
        // Phòng ban là dữ liệu của tenant: lấy từ membership ở tenant đã chọn (không dùng users.department_id toàn cục)
        UUID departmentId = tenantId != null ? choice.departmentId() : null;
        // Role/permission thuộc tenant: chỉ tính role của tenant đã chọn; phiên nền tảng không có role tenant nào
        UserAccessPort.UserAccess access = tenantId != null
                ? TenantContext.callAs(tenantId, () -> userAccessPort.fetch(credential.userId()))
                : new UserAccessPort.UserAccess(credential.userId(), List.of(), List.of(), false);
        // Phải đổi mật khẩu trước: token không mang permission nào nên mọi endpoint kiểm quyền (ở mọi service) đều
        // từ chối, chỉ còn các endpoint chỉ cần đăng nhập, như đổi mật khẩu. Vẫn mang tenantId.
        // Module của tenant đã chọn (ADR-0009): đọc ở mọi đường cấp token (đăng nhập, làm mới, đổi tenant); phiên nền
        // tảng không có tenant thì không có claim. Bảng toàn cục, đọc bằng transaction ngắn của riêng nó (R20).
        // Dữ liệu này đã có sẵn trong choice.available() (TenantSummary.modules = module đang bật, cùng nguồn với
        // modules(tenantId)); chỉ đọc lại khi tenant không nằm trong danh sách (vd. platform admin làm thay tenant).
        List<String> modules = tenantId != null ? modulesOf(tenantId, choice) : null;
        // ADR-0031 D4: tenant ở chế độ chỉ đọc thì token mang tenantAccess=READ_ONLY; platform admin không bị áp
        String tenantAccessClaim = tenantId != null && !credential.platformAdmin()
                && tenantAccessMode.modeOf(tenantId) == TenantAccessModePort.Mode.READ_ONLY
                ? JwtClaims.TENANT_ACCESS_READ_ONLY : null;
        // Họ phiên quyết định MỘT chỗ này: đăng nhập thì phiên mới, xoay vòng thì chép phiên của token cũ
        String rawRefresh = codec.generate();
        String hash = codec.hash(rawRefresh);
        RefreshToken refresh = tenantId != null
                ? RefreshToken.issueForTenant(credential.userId(), tenantId, hash, clock.instant(), refreshTokenTtl,
                        channel)
                : RefreshToken.issueForPlatform(credential.userId(), hash, clock.instant(), refreshTokenTtl);
        if (replacedOrNull != null) {
            refresh = refresh.inSession(replacedOrNull.sessionId(), replacedOrNull.sessionStartedAt());
        }
        boolean hasUa = client != null && client.userAgent() != null;
        boolean hasIp = client != null && client.ip() != null;
        String device = hasUa ? DeviceSummary.of(client.userAgent())
                : (replacedOrNull != null ? replacedOrNull.deviceSummary() : null);
        String ip = hasIp ? client.ip() : (replacedOrNull != null ? replacedOrNull.clientIp() : null);
        refresh = refresh.withDevice(device, ip);
        TokenIssuerPort.IssuedToken accessToken = tokenIssuer.issue(new TokenIssuerPort.Claims(
                credential.userId(), credential.username(), departmentId,
                access.roles(), mustChange ? List.of() : permissionsFor(access.permissions(), storefront), mustChange,
                !storefront && !mustChange && access.admin(), tenantId,
                !storefront && !mustChange && credential.platformAdmin(), modules,
                storefront ? JwtClaims.AUDIENCE_STOREFRONT : null, refresh.sessionId(), tenantAccessClaim));

        if (replacedOrNull != null) replacedOrNull.revoke(RevokeReason.ROTATED, clock.instant());
        refreshTokens.rotate(replacedOrNull, refresh);
        return TokenResponse.bearer(accessToken.value(), rawRefresh, accessToken.expiresInSeconds(), mustChange,
                tenantId, choice.available());
    }

    private List<String> modulesOf(UUID tenantId, TenantChoice choice) {
        return choice.available().stream().filter(t -> tenantId.equals(t.id())).findFirst()
                .map(TenantSummary::modules)
                .orElseGet(() -> tenantAccess.modules(tenantId));
    }

    /**
     * Kênh STOREFRONT chỉ mang khóa thuộc allowlist: token chạy trong trình duyệt của website tenant kiểm soát, nên dù
     * tài khoản (nhân viên đăng nhập ở storefront, hoặc role khách bị gán thêm khóa) có khóa khác thì cũng không vào token.
     */
    private List<String> permissionsFor(List<String> permissions, boolean storefront) {
        if (!storefront) return permissions;
        return permissions.stream().filter(this::allowedOnStorefront).toList();
    }

    private boolean allowedOnStorefront(String key) {
        for (String rule : storefrontPermissions) {
            if (rule.endsWith("/*") ? key.startsWith(rule.substring(0, rule.length() - 1)) : key.equals(rule)) {
                return true;
            }
        }
        return false;
    }
}
