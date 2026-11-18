package lms.identity.auth.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.identity.auth.application.ClientAuthenticationPort;
import lms.identity.auth.application.ClientAuthenticationPort.ClientPrincipal;
import lms.identity.auth.application.IssueClientTokenUseCase;
import lms.identity.auth.application.IssueClientTokenUseCase.IssuedClientToken;
import lms.identity.auth.domain.ClientTokenException;
import lms.identity.auth.domain.ClientTokenException.Error;
import lms.identity.auth.infrastructure.security.JwtTokenIssuer;
import lms.identity.auth.infrastructure.security.PemKeys;
import lms.security.CallerIdentity;
import lms.security.JwtClaims;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Token endpoint OAuth2 client_credentials (pha I2): thứ tự lỗi, quy tắc scope (con của đã cấp, loại theo module, rỗng =
 * invalid_scope), và claim chính xác của token (không đặc quyền nhân viên, TTL 5 phút, tương thích CallerIdentity).
 */
class IssueClientTokenUseCaseTest {

    private static final Instant NOW = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    private static final UUID APP = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();
    private static final String SECRET = "lms_sk_secret-hop-le";

    private final RSAKey key = newKey();
    private final JwtTokenIssuer issuer = new JwtTokenIssuer(new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key))),
            key.getKeyID(), "library-auth-service", Duration.ofMinutes(15), Clock.fixed(NOW, ZoneOffset.UTC));

    private ClientPrincipal principal = new ClientPrincipal(APP, TENANT,
            List.of("books:read", "inventory:read", "orders:read"), List.of("books:read", "inventory:read", "orders:read"),
            List.of("LENDING", "SALES"));
    private int authCalls;

    private final ClientAuthenticationPort clients = (id, secret) -> {
        authCalls++;
        return APP.toString().equals(id) && SECRET.equals(secret) ? Optional.of(principal) : Optional.empty();
    };

    private final IssueClientTokenUseCase useCase = new IssueClientTokenUseCase(clients, issuer, Duration.ofMinutes(5));

    private static RSAKey newKey() {
        try {
            KeyPair pair = PemKeys.generate();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyIDFromThumbprint().build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Jwt decode(String token) throws Exception {
        return NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build().decode(token);
    }

    private IssuedClientToken issue(String scope) {
        return useCase.execute("client_credentials", APP.toString(), SECRET, scope);
    }

    private static Error errorOf(Runnable r) {
        try {
            r.run();
        } catch (ClientTokenException e) {
            return e.error();
        }
        return null;
    }

    @Test
    void khongCoScopeCapMoiScopeDaCapVaClaimChinhXac() throws Exception {
        IssuedClientToken t = issue(null);

        assertThat(t.expiresIn()).isEqualTo(300);
        assertThat(t.scope()).isEqualTo("books:read inventory:read orders:read");
        Jwt jwt = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build().decode(t.accessToken());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("library-auth-service");
        assertThat(jwt.getSubject()).isEqualTo(APP.toString());
        assertThat(jwt.getAudience()).containsExactly("lms-integration");
        assertThat(jwt.getClaimAsString(JwtClaims.CLIENT_ID)).isEqualTo(APP.toString());
        assertThat(jwt.getClaimAsString(JwtClaims.APPLICATION_ID)).isEqualTo(APP.toString());
        assertThat(jwt.getClaimAsString(JwtClaims.TENANT_ID)).isEqualTo(TENANT.toString());
        assertThat(jwt.getClaimAsString(JwtClaims.SCOPE)).isEqualTo("books:read inventory:read orders:read");
        assertThat(jwt.getClaimAsStringList(JwtClaims.MODULES)).containsExactlyInAnyOrder("LENDING", "SALES");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(jwt.getHeaders()).containsEntry("kid", key.getKeyID()).containsEntry("alg", "RS256");
    }

    @Test
    void tokenKhongCoBatKyDacQuyenNhanVienNao() throws Exception {
        Jwt jwt = decode(issue(null).accessToken());

        for (String forbidden : List.of(JwtClaims.ADMIN, JwtClaims.PLATFORM_ADMIN, JwtClaims.ROLES,
                JwtClaims.PERMISSIONS, JwtClaims.SERVICE, JwtClaims.SESSION_ID, JwtClaims.USERNAME,
                JwtClaims.DEPARTMENT_ID, JwtClaims.MUST_CHANGE_PASSWORD)) {
            assertThat(jwt.hasClaim(forbidden)).as("claim %s", forbidden).isFalse();
        }
    }

    @Test
    void tokenNhanDienLaIntegrationClientQuaCallerIdentity() throws Exception {
        Jwt jwt = decode(issue("books:read").accessToken());

        CallerIdentity caller = CallerIdentity.from(jwt);

        assertThat(caller.type()).isEqualTo(CallerIdentity.AuthType.INTEGRATION);
        assertThat(caller.tenantId()).isEqualTo(TENANT);
        assertThat(caller.applicationId()).isEqualTo(APP);
        assertThat(caller.scopes()).containsExactly("books:read");
    }

    @Test
    void scopeYeuCauLaTapConCuaScopeDaCap() {
        assertThat(issue("orders:read books:read").scope()).isEqualTo("books:read orders:read");
        assertThat(issue("  books:read  ").scope()).isEqualTo("books:read");
        assertThat(issue("books:read books:read").scope()).isEqualTo("books:read");
    }

    @Test
    void scopeNgoaiDanhMucHoacChuaCapLaInvalidScope() {
        principal = new ClientPrincipal(APP, TENANT, List.of("books:read"), List.of("books:read"), List.of("LENDING"));

        assertThat(errorOf(() -> issue("orders:read"))).isEqualTo(Error.INVALID_SCOPE);
        assertThat(errorOf(() -> issue("books:read orders:read"))).isEqualTo(Error.INVALID_SCOPE);
        assertThat(errorOf(() -> issue("books:write"))).isEqualTo(Error.INVALID_SCOPE);
        assertThat(errorOf(() -> issue("admin"))).isEqualTo(Error.INVALID_SCOPE);
    }

    @Test
    void tenantTatModuleLoaiScopeVaRongThiInvalidScope() {
        principal = new ClientPrincipal(APP, TENANT, List.of("books:read", "inventory:read", "orders:read"),
                List.of("books:read"), List.of("LENDING"));

        assertThat(issue(null).scope()).isEqualTo("books:read");
        assertThat(issue("books:read inventory:read").scope()).isEqualTo("books:read");
        assertThat(errorOf(() -> issue("orders:read"))).isEqualTo(Error.INVALID_SCOPE);

        principal = new ClientPrincipal(APP, TENANT, List.of("orders:read"), List.of(), List.of("LENDING"));
        assertThat(errorOf(() -> issue(null))).isEqualTo(Error.INVALID_SCOPE);
    }

    @Test
    void applicationChuaCapScopeNaoThiInvalidScope() {
        principal = new ClientPrincipal(APP, TENANT, List.of(), List.of(), List.of("SALES"));

        assertThat(errorOf(() -> issue(null))).isEqualTo(Error.INVALID_SCOPE);
    }

    @Test
    void xacThucClientThatBaiLaInvalidClientDuLyDoGi() {
        assertThat(errorOf(() -> useCase.execute("client_credentials", APP.toString(), "sai", null)))
                .isEqualTo(Error.INVALID_CLIENT);
        assertThat(errorOf(() -> useCase.execute("client_credentials", UUID.randomUUID().toString(), SECRET, null)))
                .isEqualTo(Error.INVALID_CLIENT);
        assertThat(errorOf(() -> useCase.execute("client_credentials", null, SECRET, null)))
                .isEqualTo(Error.INVALID_CLIENT);
        assertThat(errorOf(() -> useCase.execute("client_credentials", APP.toString(), null, null)))
                .isEqualTo(Error.INVALID_CLIENT);
        assertThat(errorOf(() -> useCase.execute("client_credentials", APP.toString(), "", null)))
                .isEqualTo(Error.INVALID_CLIENT);
    }

    @Test
    void grantTypeThieuHoacSaiBiTuChoiTruocKhiChamDb() {
        assertThat(errorOf(() -> useCase.execute(null, APP.toString(), SECRET, null))).isEqualTo(Error.INVALID_REQUEST);
        assertThat(errorOf(() -> useCase.execute(" ", APP.toString(), SECRET, null))).isEqualTo(Error.INVALID_REQUEST);
        assertThat(errorOf(() -> useCase.execute("password", APP.toString(), SECRET, null)))
                .isEqualTo(Error.UNSUPPORTED_GRANT_TYPE);
        assertThat(errorOf(() -> useCase.execute("authorization_code", APP.toString(), SECRET, null)))
                .isEqualTo(Error.UNSUPPORTED_GRANT_TYPE);
        assertThat(errorOf(() -> useCase.execute("Client_Credentials", APP.toString(), SECRET, null)))
                .isEqualTo(Error.UNSUPPORTED_GRANT_TYPE);
        assertThat(authCalls).isZero();
    }

    @Test
    void thongDiepLoiLaHangKhongChuaDuLieuClientGui() {
        String hostile = "<script>" + APP;

        assertThatThrownBy(() -> useCase.execute("client_credentials", hostile, "x", null))
                .isInstanceOf(ClientTokenException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("script").doesNotContain(APP.toString()));
    }

    @Test
    void ketQuaVaTokenKhongInRaAccessTokenTrongToString() {
        IssuedClientToken t = issue(null);

        assertThat(t.toString()).doesNotContain(t.accessToken());
    }
}
