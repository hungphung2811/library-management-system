package lms.identity.auth.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.identity.auth.application.AuthDtos.TokenResponse;
import lms.identity.auth.application.LoginUseCase;
import lms.identity.auth.application.PasswordHasherPort;
import lms.identity.auth.application.PublishAuthEventPort;
import lms.identity.auth.application.RefreshTokenCodecPort;
import lms.identity.auth.application.RefreshTokenUseCase;
import lms.identity.auth.application.SaveCredentialPort;
import lms.identity.auth.application.SaveRefreshTokenPort;
import lms.identity.auth.application.SwitchTenantSessionUseCase;
import lms.identity.auth.application.TenantAccessPort;
import lms.identity.auth.application.TokenIssuerPort;
import lms.identity.auth.application.TokenService;
import lms.identity.auth.application.UserAccessPort;
import lms.identity.auth.domain.InvalidCredentialsException;
import lms.identity.auth.domain.InvalidRefreshTokenException;
import lms.identity.auth.domain.RefreshToken;
import lms.identity.auth.domain.RefreshTokenRepository;
import lms.identity.auth.domain.TokenChannel;
import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.UserCredentialRepository;
import lms.identity.auth.infrastructure.security.JwtTokenIssuer;
import lms.identity.auth.infrastructure.security.PemKeys;
import lms.identity.tenant.application.TenantChoice;
import lms.identity.tenant.application.TenantSummary;
import lms.security.JwtClaims;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Kênh token (customer-api-data-authorization A1/A18, 5.3 phương án C): token storefront có aud riêng, KHÔNG admin/
 * platformAdmin dù tài khoản có role admin; refresh giữ kênh và không chéo kênh; JwtTokenIssuer ghi aud đúng.
 */
class TokenChannelTest {

    private final Instant now = Instant.parse("2026-11-17T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final UUID userId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final TenantSummary summary = new TenantSummary(tenantId, "shop", "Cửa hàng");

    private final List<TokenIssuerPort.Claims> issued = new ArrayList<>();
    private final List<RefreshToken> saved = new ArrayList<>();
    private final TenantAccessPort tenants = mock(TenantAccessPort.class);
    private final RefreshTokenRepository refreshRepo = mock(RefreshTokenRepository.class);
    private final UserCredentialRepository credentials = mock(UserCredentialRepository.class);
    private final SaveRefreshTokenPort refreshSave = new SaveRefreshTokenPort() {
        public RefreshToken save(RefreshToken t) { saved.add(t); return t; }
        public void revokeAllByUserId(UUID u) { }
    };
    private final RefreshTokenCodecPort codec = new RefreshTokenCodecPort() {
        public String generate() { return "raw-new"; }
        public String hash(String raw) { return "h-" + raw; }
    };
    // tài khoản có role admin: access.admin() = true
    private final TokenService service = new TokenService(
            id -> new UserAccessPort.UserAccess(id, List.of("OWNER"), List.of("ShopController/placeOrder"), true),
            c -> { issued.add(c); return new TokenIssuerPort.IssuedToken("jwt", 900); },
            codec, refreshSave, clock, Duration.ofDays(7), id -> Optional.empty(), tenants);

    private UserCredential credential(boolean platformAdmin) {
        return UserCredential.restore(userId, "alice", "h", null, true, 0, null, false, platformAdmin);
    }

    @Test
    void kenhStorefrontBoAdminVaPlatformAdminDuTaiKhoanLaAdmin() {
        when(tenants.selectForLogin(userId, "shop", true)).thenReturn(new TenantChoice(tenantId, List.of(summary)));

        TokenResponse r = service.issueFor(credential(true), "shop", TokenChannel.STOREFRONT);

        TokenIssuerPort.Claims c = issued.get(0);
        assertThat(c.audience()).isEqualTo(JwtClaims.AUDIENCE_STOREFRONT);
        assertThat(c.admin()).isFalse();
        assertThat(c.platformAdmin()).isFalse();
        assertThat(c.tenantId()).isEqualTo(tenantId);
        assertThat(c.permissions()).containsExactly("ShopController/placeOrder"); // khóa của role vẫn có (không admin)
        assertThat(r.tenantId()).isEqualTo(tenantId);
        assertThat(saved.get(0).channel()).isEqualTo(TokenChannel.STOREFRONT);
    }

    @Test
    void kenhNhanVienGiuHanhViCu() {
        when(tenants.selectForLogin(userId, "shop", false)).thenReturn(new TenantChoice(tenantId, List.of(summary)));

        service.issueFor(credential(false), "shop", TokenChannel.STAFF);

        TokenIssuerPort.Claims c = issued.get(0);
        assertThat(c.audience()).isNull();
        assertThat(c.admin()).isTrue();
        assertThat(saved.get(0).channel()).isEqualTo(TokenChannel.STAFF);
        // overload cũ (không kênh) cũng là STAFF
        issued.clear();
        service.issueFor(credential(false), "shop");
        assertThat(issued.get(0).audience()).isNull();
        assertThat(saved.get(1).channel()).isEqualTo(TokenChannel.STAFF);
    }

    @Test
    void phienStorefrontKhongCoTenantBiTuChoi() {
        when(tenants.selectForLogin(userId, "x", true)).thenReturn(new TenantChoice(null, List.of()));

        assertThatThrownBy(() -> service.issueFor(credential(true), "x", TokenChannel.STOREFRONT))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThat(issued).isEmpty();
        assertThat(saved).isEmpty();
    }

    @Test
    void refreshGiuKenhStorefrontVaVanKhongAdmin() {
        RefreshToken old = RefreshToken.issueForTenant(userId, tenantId, "h-raw-old", now.minusSeconds(60),
                Duration.ofDays(7), TokenChannel.STOREFRONT);
        when(refreshRepo.findByTokenHash("h-raw-old")).thenReturn(Optional.of(old));
        when(credentials.findByUserId(userId)).thenReturn(Optional.of(credential(false)));
        when(tenants.verify(userId, tenantId)).thenReturn(new TenantChoice(tenantId, List.of(summary)));
        RefreshTokenUseCase refresh = new RefreshTokenUseCase(refreshRepo, refreshSave, credentials, codec, service,
                tenants, clock);

        refresh.execute("raw-old", TokenChannel.STOREFRONT);

        assertThat(old.isRevoked()).isTrue();
        assertThat(issued.get(0).audience()).isEqualTo(JwtClaims.AUDIENCE_STOREFRONT);
        assertThat(issued.get(0).admin()).isFalse();
        assertThat(saved).anySatisfy(t -> {
            assertThat(t.channel()).isEqualTo(TokenChannel.STOREFRONT);
            assertThat(t.tokenHash()).isEqualTo("h-raw-new");
        });
    }

    @Test
    void refreshChéoKenhBiTuChoiVaKhongXoayVongHayThuHoi() {
        RefreshToken storefront = RefreshToken.issueForTenant(userId, tenantId, "h-raw-sf", now.minusSeconds(60),
                Duration.ofDays(7), TokenChannel.STOREFRONT);
        RefreshToken staff = RefreshToken.issueForTenant(userId, tenantId, "h-raw-st", now.minusSeconds(60),
                Duration.ofDays(7));
        when(refreshRepo.findByTokenHash("h-raw-sf")).thenReturn(Optional.of(storefront));
        when(refreshRepo.findByTokenHash("h-raw-st")).thenReturn(Optional.of(staff));
        RefreshTokenUseCase refresh = new RefreshTokenUseCase(refreshRepo, refreshSave, credentials, codec, service,
                tenants, clock);

        // lms-api (STAFF) làm mới token storefront -> 401; customer-api (STOREFRONT) làm mới token nhân viên -> 401
        assertThatThrownBy(() -> refresh.execute("raw-sf")).isInstanceOf(InvalidRefreshTokenException.class);
        assertThatThrownBy(() -> refresh.execute("raw-sf", TokenChannel.STAFF))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThatThrownBy(() -> refresh.execute("raw-st", TokenChannel.STOREFRONT))
                .isInstanceOf(InvalidRefreshTokenException.class);

        assertThat(storefront.isRevoked()).isFalse();
        assertThat(staff.isRevoked()).isFalse();
        assertThat(issued).isEmpty();
        assertThat(saved).isEmpty();
    }

    @Test
    void doiTenantKhongDungChoPhienStorefront() {
        RefreshToken storefront = RefreshToken.issueForTenant(userId, tenantId, "h-raw-sf", now.minusSeconds(60),
                Duration.ofDays(7), TokenChannel.STOREFRONT);
        when(refreshRepo.findByTokenHash("h-raw-sf")).thenReturn(Optional.of(storefront));
        when(credentials.findByUserId(userId)).thenReturn(Optional.of(credential(false)));
        SwitchTenantSessionUseCase switchTenant = new SwitchTenantSessionUseCase(credentials, refreshRepo, refreshSave,
                codec, tenants, service, clock);

        assertThatThrownBy(() -> switchTenant.execute(userId, UUID.randomUUID(), "raw-sf"))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(storefront.isRevoked()).isFalse();
        assertThat(issued).isEmpty();
    }

    // ---- allowlist permission của token STOREFRONT (vá leo thang quyền) ----

    private TokenService serviceWith(List<String> rolePermissions, java.util.Collection<String> allowlist) {
        return new TokenService(
                id -> new UserAccessPort.UserAccess(id, List.of("CUSTOMER"), rolePermissions, false),
                c -> { issued.add(c); return new TokenIssuerPort.IssuedToken("jwt", 900); },
                codec, refreshSave, clock, Duration.ofDays(7), id -> Optional.empty(), tenants, allowlist);
    }

    private static final List<String> MIXED = List.of("ShopController/placeOrder", "ShopController/myOrders",
            "TitleController/lookup", "StorefrontController/list", "SalesOrderController/markPaid",
            "RefundController/markRefunded", "AccessControlController/grantPermission");

    @Test
    void tokenStorefrontChiMangKhoaTrongAllowlistMacDinh() {
        when(tenants.selectForLogin(userId, "shop", false)).thenReturn(new TenantChoice(tenantId, List.of(summary)));

        serviceWith(MIXED, TokenService.DEFAULT_STOREFRONT_PERMISSIONS)
                .issueFor(credential(false), "shop", TokenChannel.STOREFRONT);

        assertThat(issued.get(0).permissions()).containsExactly("ShopController/placeOrder",
                "ShopController/myOrders", "TitleController/lookup");
    }

    @Test
    void tokenNhanVienKhongBiLocPermission() {
        when(tenants.selectForLogin(userId, "shop", false)).thenReturn(new TenantChoice(tenantId, List.of(summary)));

        serviceWith(MIXED, TokenService.DEFAULT_STOREFRONT_PERMISSIONS)
                .issueFor(credential(false), "shop", TokenChannel.STAFF);

        assertThat(issued.get(0).permissions()).containsExactlyElementsOf(MIXED);
    }

    @Test
    void allowlistCauHinhDuocKhopChinhXacHoacTheoTienToController() {
        when(tenants.selectForLogin(userId, "shop", false)).thenReturn(new TenantChoice(tenantId, List.of(summary)));

        serviceWith(MIXED, List.of(" StorefrontController/* ", "TitleController/lookup", ""))
                .issueFor(credential(false), "shop", TokenChannel.STOREFRONT);

        assertThat(issued.get(0).permissions()).containsExactly("TitleController/lookup", "StorefrontController/list");
        // "ShopController/*" không khớp nhầm controller có tên bắt đầu giống nhau
        issued.clear();
        serviceWith(List.of("ShopController/placeOrder", "ShopControllerExtra/x"), List.of("ShopController/*"))
                .issueFor(credential(false), "shop", TokenChannel.STOREFRONT);
        assertThat(issued.get(0).permissions()).containsExactly("ShopController/placeOrder");
    }

    @Test
    void refreshTokenStorefrontCungBiLocPermission() {
        RefreshToken old = RefreshToken.issueForTenant(userId, tenantId, "h-raw-old", now.minusSeconds(60),
                Duration.ofDays(7), TokenChannel.STOREFRONT);
        when(refreshRepo.findByTokenHash("h-raw-old")).thenReturn(Optional.of(old));
        when(credentials.findByUserId(userId)).thenReturn(Optional.of(credential(false)));
        when(tenants.verify(userId, tenantId)).thenReturn(new TenantChoice(tenantId, List.of(summary)));
        TokenService filtered = serviceWith(MIXED, TokenService.DEFAULT_STOREFRONT_PERMISSIONS);

        new RefreshTokenUseCase(refreshRepo, refreshSave, credentials, codec, filtered, tenants, clock)
                .execute("raw-old", TokenChannel.STOREFRONT);

        assertThat(issued.get(0).permissions()).containsExactly("ShopController/placeOrder",
                "ShopController/myOrders", "TitleController/lookup");
    }

    // ---- đăng nhập ----

    private LoginUseCase login(UserCredential stored) {
        UserCredentialRepository repo = mock(UserCredentialRepository.class);
        when(repo.findByUsername("alice")).thenReturn(Optional.of(stored));
        PasswordHasherPort hasher = mock(PasswordHasherPort.class);
        when(hasher.matches("secret123", "h")).thenReturn(true);
        return new LoginUseCase(repo, mock(SaveCredentialPort.class), hasher, service, mock(PublishAuthEventPort.class),
                clock, tenants);
    }

    @Test
    void dangNhapStorefrontBatBuocCoMaTenantTruocKhiDungToiTaiKhoan() {
        assertThatThrownBy(() -> login(credential(false)).execute("alice", "secret123", null, TokenChannel.STOREFRONT))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> login(credential(false)).execute("alice", "secret123", " ", TokenChannel.STOREFRONT))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThat(issued).isEmpty();
    }

    @Test
    void dangNhapStorefrontCapTokenHanChe() {
        when(tenants.selectForLogin(userId, "shop", false)).thenReturn(new TenantChoice(tenantId, List.of(summary)));

        TokenResponse r = login(credential(false)).execute("alice", "secret123", "shop", TokenChannel.STOREFRONT);

        assertThat(r.tenantId()).isEqualTo(tenantId);
        assertThat(issued.get(0).audience()).isEqualTo(JwtClaims.AUDIENCE_STOREFRONT);
        assertThat(issued.get(0).admin()).isFalse();
    }

    // ---- JwtTokenIssuer ----

    private record Rsa(JwtTokenIssuer issuer, NimbusJwtDecoder decoder) {
    }

    private static Rsa rsa(boolean staffAudience) throws Exception {
        KeyPair pair = PemKeys.generate();
        RSAKey key = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate())
                .keyIDFromThumbprint().build();
        JwtTokenIssuer issuer = new JwtTokenIssuer(new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key))),
                key.getKeyID(), "library-auth-service", Duration.ofMinutes(15), Clock.systemUTC(), staffAudience);
        return new Rsa(issuer, NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build());
    }

    private Jwt issue(Rsa rsa, String audience, boolean admin, boolean platformAdmin) {
        return rsa.decoder().decode(rsa.issuer().issue(new TokenIssuerPort.Claims(userId, "alice", null,
                List.of("OWNER"), List.of("k"), false, admin, tenantId, platformAdmin, List.of("SALES"), audience))
                .value());
    }

    @Test
    void jwtKenhStorefrontCoAudVaKhongBaoGioCoAdminHayPlatformAdminKeCaKhiClaimsDoiHoi() throws Exception {
        Rsa rsa = rsa(false);

        Jwt jwt = issue(rsa, JwtClaims.AUDIENCE_STOREFRONT, true, true);

        assertThat(jwt.getAudience()).containsExactly("lms-storefront");
        assertThat(jwt.hasClaim(JwtClaims.ADMIN)).isFalse();
        assertThat(jwt.hasClaim(JwtClaims.PLATFORM_ADMIN)).isFalse();
        assertThat(jwt.getClaimAsString(JwtClaims.TENANT_ID)).isEqualTo(tenantId.toString());
    }

    @Test
    void jwtKenhNhanVienKhongCoAudMacDinhVaGiuAdmin() throws Exception {
        Jwt jwt = issue(rsa(false), null, true, true);

        assertThat(jwt.hasClaim(JwtClaims.AUDIENCE)).isFalse();
        assertThat(jwt.getClaimAsBoolean(JwtClaims.ADMIN)).isTrue();
        assertThat(jwt.getClaimAsBoolean(JwtClaims.PLATFORM_ADMIN)).isTrue();
    }

    @Test
    void jwtKenhNhanVienCoAudStaffKhiBatCoCauHinh() throws Exception {
        Rsa rsa = rsa(true);

        assertThat(issue(rsa, null, true, false).getAudience()).containsExactly("lms-staff");
        // token storefront vẫn là lms-storefront, không bị ghi đè bởi cờ staff
        assertThat(issue(rsa, JwtClaims.AUDIENCE_STOREFRONT, false, false).getAudience())
                .containsExactly("lms-storefront");
    }

    @Test
    void parseKenh() {
        assertThat(TokenChannel.parse(null)).isEqualTo(TokenChannel.STAFF);
        assertThat(TokenChannel.parse(" ")).isEqualTo(TokenChannel.STAFF);
        assertThat(TokenChannel.parse("storefront")).isEqualTo(TokenChannel.STOREFRONT);
        assertThatThrownBy(() -> TokenChannel.parse("ADMIN")).isInstanceOf(IllegalArgumentException.class);
    }
}
