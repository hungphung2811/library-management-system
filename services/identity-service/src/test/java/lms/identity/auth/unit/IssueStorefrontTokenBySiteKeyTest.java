package lms.identity.auth.unit;

import static org.assertj.core.api.Assertions.assertThat;

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
import lms.identity.auth.application.IssueStorefrontTokenUseCase;
import lms.identity.auth.application.SiteKeyTenantPort;
import lms.identity.auth.application.SiteKeyTenantPort.SiteKeyTenant;
import lms.identity.auth.infrastructure.security.JwtTokenIssuer;
import lms.identity.auth.infrastructure.security.PemKeys;
import lms.identity.tenant.application.StorefrontTenant;
import lms.security.JwtClaims;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Token svc cấp theo site key (pha P2): thêm claim application_id, giữ nguyên các claim svc; token theo {code} không có claim này. */
class IssueStorefrontTokenBySiteKeyTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID applicationId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-16T00:00:00Z");

    private record Fixture(IssueStorefrontTokenUseCase useCase, NimbusJwtDecoder decoder) {
    }

    private Fixture fixture(SiteKeyTenantPort siteKeys) throws Exception {
        KeyPair pair = PemKeys.generate();
        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate())
                .keyIDFromThumbprint().build();
        JwtTokenIssuer issuer = new JwtTokenIssuer(new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey))),
                rsaKey.getKeyID(), "library-auth-service", Duration.ofMinutes(15), Clock.fixed(now, ZoneOffset.UTC));
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
        decoder.setJwtValidator(token -> OAuth2TokenValidatorResult.success());
        return new Fixture(new IssueStorefrontTokenUseCase(code -> Optional.of(new StorefrontTenant(tenantId, "shop-a",
                "Hiệu sách A", List.of("SALES"))), issuer, Duration.ofMinutes(5), siteKeys), decoder);
    }

    @Test
    void tokenTheoSiteKeyMangApplicationIdVaGiuCacClaimSvc() throws Exception {
        Fixture f = fixture(key -> Optional.of(new SiteKeyTenant(applicationId, new StorefrontTenant(tenantId, "shop-a",
                "Hiệu sách A", List.of("SALES"), List.of("https://shop.a.vn")))));

        var access = f.useCase().executeBySiteKey("lms_pk_" + "A".repeat(32)).orElseThrow();
        Jwt jwt = f.decoder().decode(access.accessToken());

        assertThat(access.applicationId()).isEqualTo(applicationId);
        assertThat(access.tenant().allowedOrigins()).containsExactly("https://shop.a.vn");
        assertThat(jwt.getClaimAsString(JwtClaims.APPLICATION_ID)).isEqualTo(applicationId.toString());
        assertThat(jwt.getClaimAsString(JwtClaims.SERVICE)).isEqualTo(JwtClaims.SERVICE_CUSTOMER_API);
        assertThat(jwt.getClaimAsString(JwtClaims.TENANT_ID)).isEqualTo(tenantId.toString());
        assertThat(jwt.getClaimAsStringList(JwtClaims.PERMISSIONS)).isEmpty();
        assertThat(jwt.getSubject()).isEqualTo(IssueStorefrontTokenUseCase.STOREFRONT_SUBJECT.toString());
        assertThat(jwt.getExpiresAt()).isEqualTo(now.plus(Duration.ofMinutes(5)));
        assertThat(jwt.hasClaim(JwtClaims.ADMIN)).isFalse();
        assertThat(jwt.hasClaim(JwtClaims.CLIENT_ID)).isFalse();
        assertThat(jwt.hasClaim(JwtClaims.SCOPE)).isFalse();
        assertThat(jwt.getAudience()).isNullOrEmpty();
    }

    @Test
    void tokenTheoMaTenantKhongCoApplicationId() throws Exception {
        Fixture f = fixture(key -> Optional.empty());

        var access = f.useCase().execute("shop-a").orElseThrow();

        assertThat(access.applicationId()).isNull();
        assertThat(f.decoder().decode(access.accessToken()).hasClaim(JwtClaims.APPLICATION_ID)).isFalse();
    }

    @Test
    void siteKeyKhongKhaDungTraRongKhongPhatToken() throws Exception {
        Fixture f = fixture(key -> Optional.empty());

        assertThat(f.useCase().executeBySiteKey("lms_pk_" + "A".repeat(32))).isEmpty();
    }

    @Test
    void khongCauHinhSiteKeyThiLuonRong() throws Exception {
        Fixture f = fixture(null);

        assertThat(f.useCase().executeBySiteKey("lms_pk_" + "A".repeat(32))).isEmpty();
    }
}
