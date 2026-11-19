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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lms.identity.auth.application.RefreshTokenCodecPort;
import lms.identity.auth.application.SaveRefreshTokenPort;
import lms.identity.auth.application.TenantAccessModePort;
import lms.identity.auth.application.TenantAccessPort;
import lms.identity.auth.application.TenantDataTokenIssuerPort;
import lms.identity.auth.application.TokenIssuerPort;
import lms.identity.auth.application.TokenService;
import lms.identity.auth.application.UserAccessPort;
import lms.identity.auth.domain.RefreshToken;
import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.infrastructure.security.JwtTokenIssuer;
import lms.identity.auth.infrastructure.security.PemKeys;
import lms.identity.tenant.application.TenantChoice;
import lms.identity.tenant.application.TenantSummary;
import lms.security.JwtClaims;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** ADR-0031 (gói F0): claim tenantAccess ở token người dùng và token dịch vụ tenant-data. */
class TenantAccessClaimTest {

    private final Clock clock = Clock.fixed(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS), ZoneOffset.UTC);
    private final UUID userId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final TenantSummary summary = new TenantSummary(tenantId, "t", "T", List.of("LENDING"));

    private final List<TokenIssuerPort.Claims> issued = new ArrayList<>();
    private final SaveRefreshTokenPort refreshSave = new SaveRefreshTokenPort() {
        public RefreshToken save(RefreshToken t) { return t; }
        public void revokeAllByUserId(UUID u) { }
        public int revokeSession(UUID s, lms.identity.auth.domain.RevokeReason r, Instant n) { return 1; }
    };
    private final RefreshTokenCodecPort codec = new RefreshTokenCodecPort() {
        public String generate() { return "raw"; }
        public String hash(String raw) { return "h-" + raw; }
    };
    private final TenantAccessPort tenants = org.mockito.Mockito.mock(TenantAccessPort.class);

    private TokenService service(TenantAccessModePort mode) {
        return new TokenService(id -> new UserAccessPort.UserAccess(id, List.of("R"), List.of("k"), true),
                c -> { issued.add(c); return new TokenIssuerPort.IssuedToken("jwt", 900); },
                codec, refreshSave, clock, Duration.ofDays(7), id -> java.util.Optional.empty(), tenants,
                List.of("ShopController/*"), mode);
    }

    private UserCredential credential(boolean platformAdmin) {
        return UserCredential.restore(userId, "alice", "h", null, true, 0, null, false, platformAdmin);
    }

    private JwtTokenIssuer jwtIssuer(RSAKey rsaKey) {
        return new JwtTokenIssuer(new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey))), rsaKey.getKeyID(),
                "library-auth-service", Duration.ofMinutes(15), clock);
    }

    private static RSAKey newKey() throws Exception {
        KeyPair pair = PemKeys.generate();
        return new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate())
                .keyIDFromThumbprint().build();
    }

    @Test
    void macDinhFullAlwaysKhongGhiClaimChiDoc() {
        service(TenantAccessModePort.FULL_ALWAYS).issueForTenant(credential(false), new TenantChoice(tenantId,
                List.of(summary), null));

        assertThat(issued.get(0).tenantAccess()).isNull();
    }

    @Test
    void khongTruyenPortThiMacDinhFull() {
        TokenService legacy = new TokenService(id -> new UserAccessPort.UserAccess(id, List.of(), List.of(), false),
                c -> { issued.add(c); return new TokenIssuerPort.IssuedToken("jwt", 900); }, codec, refreshSave, clock,
                Duration.ofDays(7), id -> java.util.Optional.empty(), tenants);
        legacy.issueForTenant(credential(false), new TenantChoice(tenantId, List.of(summary), null));

        assertThat(issued.get(0).tenantAccess()).isNull();
    }

    @Test
    void tenantChiDocThiTokenNguoiDungMangReadOnly() {
        service(t -> TenantAccessModePort.Mode.READ_ONLY).issueForTenant(credential(false),
                new TenantChoice(tenantId, List.of(summary), null));

        assertThat(issued.get(0).tenantAccess()).isEqualTo(JwtClaims.TENANT_ACCESS_READ_ONLY);
    }

    @Test
    void chiDocTheoTungTenantKhongLayLanSangTenantKhac() {
        UUID other = UUID.randomUUID();
        TokenService svc = service(t -> t.equals(tenantId) ? TenantAccessModePort.Mode.READ_ONLY
                : TenantAccessModePort.Mode.FULL);
        TenantSummary otherSummary = new TenantSummary(other, "o", "O", List.of("LENDING"));
        List<TenantSummary> all = List.of(summary, otherSummary);

        svc.issueForTenant(credential(false), new TenantChoice(tenantId, all, null));
        svc.issueForTenant(credential(false), new TenantChoice(other, all, null));

        assertThat(issued.get(0).tenantAccess()).isEqualTo("READ_ONLY");
        assertThat(issued.get(1).tenantAccess()).isNull();
    }

    @Test
    void platformAdminKhongBiApChiDoc() {
        service(t -> TenantAccessModePort.Mode.READ_ONLY).issueForTenant(credential(true),
                new TenantChoice(tenantId, List.of(summary), null));
        service(t -> TenantAccessModePort.Mode.READ_ONLY).issueForTenant(credential(true),
                new TenantChoice(null, List.of(), null));

        assertThat(issued.get(0).tenantAccess()).isNull();
        assertThat(issued.get(1).tenantAccess()).isNull();
    }

    @Test
    void jwtChiGhiClaimTenantAccessKhiReadOnlyVaCoTenant() throws Exception {
        RSAKey key = newKey();
        JwtTokenIssuer issuer = jwtIssuer(key);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();

        Jwt readOnly = decoder.decode(issuer.issue(new TokenIssuerPort.Claims(userId, "a", null, List.of(), List.of("k"),
                false, true, tenantId, false, List.of("LENDING"), null, null, "READ_ONLY")).value());
        assertThat(readOnly.getClaimAsString(JwtClaims.TENANT_ACCESS)).isEqualTo("READ_ONLY");

        Jwt full = decoder.decode(issuer.issue(new TokenIssuerPort.Claims(userId, "a", null, List.of(), List.of("k"),
                false, true, tenantId, false, List.of("LENDING"), null, null, "FULL")).value());
        assertThat(full.hasClaim(JwtClaims.TENANT_ACCESS)).isFalse();

        Jwt absent = decoder.decode(issuer.issue(new TokenIssuerPort.Claims(userId, "a", null, List.of(), List.of("k"),
                false, true, tenantId, false, List.of("LENDING"), null, null)).value());
        assertThat(absent.hasClaim(JwtClaims.TENANT_ACCESS)).isFalse();

        Jwt noTenant = decoder.decode(issuer.issue(new TokenIssuerPort.Claims(userId, "root", null, List.of(),
                List.of(), false, false, null, true, null, null, null, "READ_ONLY")).value());
        assertThat(noTenant.hasClaim(JwtClaims.TENANT_ACCESS)).isFalse();
    }

    @Test
    void issueTenantDataGhiDungClaimVaTtl() throws Exception {
        RSAKey key = newKey();
        JwtTokenIssuer issuer = jwtIssuer(key);
        UUID subject = UUID.randomUUID();

        var issuedToken = issuer.issueTenantData(new TenantDataTokenIssuerPort.TenantDataClaims(subject, tenantId));
        Jwt jwt = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build().decode(issuedToken.value());

        assertThat(issuedToken.expiresInSeconds()).isEqualTo(600);
        assertThat(jwt.getClaimAsString(JwtClaims.SERVICE)).isEqualTo("tenant-data");
        assertThat(jwt.getAudience()).containsExactly("lms-tenant-data");
        assertThat(jwt.getClaimAsString(JwtClaims.TENANT_ID)).isEqualTo(tenantId.toString());
        assertThat(jwt.getSubject()).isEqualTo(subject.toString());
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getClaimAsStringList(JwtClaims.PERMISSIONS)).isEmpty();
        assertThat(jwt.getExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(10)));
        assertThat(jwt.hasClaim(JwtClaims.ADMIN)).isFalse();
        assertThat(jwt.hasClaim(JwtClaims.PLATFORM_ADMIN)).isFalse();
        assertThat(jwt.hasClaim(JwtClaims.ROLES)).isFalse();
        assertThat(jwt.hasClaim(JwtClaims.USERNAME)).isFalse();
        assertThat(jwt.hasClaim(JwtClaims.MODULES)).isFalse();
        assertThat(jwt.hasClaim(JwtClaims.TENANT_ACCESS)).isFalse();
        assertThat(jwt.hasClaim(JwtClaims.SESSION_ID)).isFalse();
    }

    @Test
    void issueTenantDataTuChoiTtlKhongHopLe() {
        UUID s = UUID.randomUUID();
        assertThatThrownBy(() -> new TenantDataTokenIssuerPort.TenantDataClaims(s, tenantId, Duration.ofMinutes(11)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TenantDataTokenIssuerPort.TenantDataClaims(s, tenantId, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TenantDataTokenIssuerPort.TenantDataClaims(null, tenantId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TenantDataTokenIssuerPort.TenantDataClaims(s, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new TenantDataTokenIssuerPort.TenantDataClaims(s, tenantId, Duration.ofMinutes(10)).ttl())
                .isEqualTo(Duration.ofMinutes(10));
    }
}
