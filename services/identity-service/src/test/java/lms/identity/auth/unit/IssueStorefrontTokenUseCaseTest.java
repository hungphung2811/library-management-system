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
import lms.identity.auth.application.StorefrontTenantPort;
import lms.identity.auth.infrastructure.security.JwtTokenIssuer;
import lms.identity.auth.infrastructure.security.PemKeys;
import lms.identity.tenant.application.StorefrontTenant;
import lms.security.JwtClaims;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Token dịch vụ storefront: đúng claim, sống theo cấu hình, không mang gì của người dùng (ADR-0011 2.1). */
class IssueStorefrontTokenUseCaseTest {

    private final UUID tenantId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-16T00:00:00Z");

    private record Fixture(IssueStorefrontTokenUseCase useCase, NimbusJwtDecoder decoder) {
    }

    private Fixture fixture(StorefrontTenantPort port) throws Exception {
        KeyPair pair = PemKeys.generate();
        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate())
                .keyIDFromThumbprint().build();
        JwtTokenIssuer issuer = new JwtTokenIssuer(new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey))),
                rsaKey.getKeyID(), "library-auth-service", Duration.ofMinutes(15),
                Clock.fixed(now, ZoneOffset.UTC));
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
        decoder.setJwtValidator(token -> org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success());
        return new Fixture(new IssueStorefrontTokenUseCase(port, issuer, Duration.ofMinutes(5)), decoder);
    }

    @Test
    void tenantKhaDungPhatTokenDichVuDungClaimVaTtl5Phut() throws Exception {
        Fixture f = fixture(code -> Optional.of(new StorefrontTenant(tenantId, "shop-a", "Hiệu sách A",
                List.of("LENDING", "SALES"))));

        var access = f.useCase().execute("shop-a").orElseThrow();
        Jwt jwt = f.decoder().decode(access.accessToken());

        assertThat(access.tenant().id()).isEqualTo(tenantId);
        assertThat(access.expiresIn()).isEqualTo(300);
        assertThat(jwt.getSubject()).isEqualTo(IssueStorefrontTokenUseCase.STOREFRONT_SUBJECT.toString());
        assertThat(jwt.getClaimAsString(JwtClaims.SERVICE)).isEqualTo(JwtClaims.SERVICE_CUSTOMER_API);
        assertThat(jwt.getClaimAsString(JwtClaims.TENANT_ID)).isEqualTo(tenantId.toString());
        assertThat(jwt.getClaimAsStringList(JwtClaims.MODULES)).containsExactly("LENDING", "SALES");
        assertThat(jwt.getClaimAsStringList(JwtClaims.PERMISSIONS)).isEmpty();
        assertThat(jwt.getExpiresAt()).isEqualTo(now.plus(Duration.ofMinutes(5)));
        assertThat(jwt.hasClaim(JwtClaims.ADMIN)).isFalse();
        assertThat(jwt.hasClaim(JwtClaims.PLATFORM_ADMIN)).isFalse();
        assertThat(jwt.hasClaim(JwtClaims.ROLES)).isFalse();
        assertThat(jwt.hasClaim(JwtClaims.USERNAME)).isFalse();
        assertThat(jwt.hasClaim(JwtClaims.MUST_CHANGE_PASSWORD)).isFalse();
    }

    @Test
    void subLaUuidHopLe() {
        assertThat(IssueStorefrontTokenUseCase.STOREFRONT_SUBJECT).isNotNull();
        assertThat(IssueStorefrontTokenUseCase.STOREFRONT_SUBJECT)
                .isNotEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    }

    @Test
    void tenantKhongKhaDungTraRongVaKhongPhatToken() throws Exception {
        Fixture f = fixture(code -> Optional.empty());

        assertThat(f.useCase().execute("khong-co")).isEmpty();
    }
}
