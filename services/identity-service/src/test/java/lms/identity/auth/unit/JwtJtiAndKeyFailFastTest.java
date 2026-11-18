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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lms.identity.auth.application.ServiceTokenIssuerPort.ServiceClaims;
import lms.identity.auth.application.TokenIssuerPort;
import lms.identity.auth.infrastructure.config.JwtKeyConfiguration;
import lms.identity.auth.infrastructure.security.JwtTokenIssuer;
import lms.identity.auth.infrastructure.security.PemKeys;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** P0: jti trên mọi loại token phát hành, và fail-fast khi thiếu khóa JWT ngoài dev/test/demo-seed. */
class JwtJtiAndKeyFailFastTest {

    // jti

    @Test
    void moiLoaiTokenCoJtiUuidVaKhongTrungNhau() throws Exception {
        KeyPair pair = PemKeys.generate();
        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate())
                .keyIDFromThumbprint().build();
        JwtTokenIssuer issuer = new JwtTokenIssuer(new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey))),
                rsaKey.getKeyID(), "library-auth-service", Duration.ofMinutes(15), Clock.systemUTC(), true);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
        UUID user = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();

        // nhân viên (có aud lms-staff vì issuer bật cờ), storefront (aud lms-storefront), svc
        Jwt staff = decoder.decode(issuer.issue(new TokenIssuerPort.Claims(user, "alice", null, List.of(),
                List.of("k"), false, false, tenant, false, List.of("LENDING"))).value());
        Jwt storefront = decoder.decode(issuer.issue(new TokenIssuerPort.Claims(user, "reader", null, List.of(),
                List.of(), false, false, tenant, false, List.of("SALES"), "lms-storefront")).value());
        Jwt service = decoder.decode(issuer.issueService(new ServiceClaims("customer-api", UUID.randomUUID(), tenant,
                List.of("SALES"), Duration.ofMinutes(5))).value());
        Jwt staffAgain = decoder.decode(issuer.issue(new TokenIssuerPort.Claims(user, "alice", null, List.of(),
                List.of("k"), false, false, tenant, false, List.of("LENDING"))).value());

        Set<String> ids = new HashSet<>();
        for (Jwt jwt : List.of(staff, storefront, service, staffAgain)) {
            assertThat(jwt.getId()).isNotBlank();
            assertThat(UUID.fromString(jwt.getId())).isNotNull();
            ids.add(jwt.getId());
        }
        assertThat(ids).hasSize(4);
    }

    @Test
    void jtiKhongLamDoiCacClaimConLai() throws Exception {
        KeyPair pair = PemKeys.generate();
        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate())
                .keyIDFromThumbprint().build();
        JwtTokenIssuer issuer = new JwtTokenIssuer(new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey))),
                rsaKey.getKeyID(), "library-auth-service", Duration.ofMinutes(15), Clock.systemUTC());
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
        UUID tenant = UUID.randomUUID();

        Jwt service = decoder.decode(issuer.issueService(new ServiceClaims("customer-api", UUID.randomUUID(), tenant,
                List.of("SALES"), Duration.ofMinutes(5))).value());

        assertThat(service.getClaimAsString("svc")).isEqualTo("customer-api");
        assertThat(service.getClaimAsString("tenantId")).isEqualTo(tenant.toString());
        assertThat(service.hasClaim("admin")).isFalse();
        assertThat(service.hasClaim("platformAdmin")).isFalse();
        assertThat(service.getAudience()).isNullOrEmpty();
    }

    // fail-fast khóa JWT

    private final JwtKeyConfiguration config = new JwtKeyConfiguration();

    private RSAKey build(String privatePem, String publicPem, boolean allow, MockEnvironment env) {
        return config.rsaKey(privatePem, publicPem, allow, env);
    }

    private static MockEnvironment profiles(String... active) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(active);
        return env;
    }

    @Test
    void prodKhongCoKhoaVaKhongBatCoThiKhoiDongThatBai() {
        assertThatThrownBy(() -> build("", "", false, profiles("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_JWT_PRIVATE_KEY")
                .hasMessageContaining("AUTH_JWT_ALLOW_EPHEMERAL_KEY");
    }

    @Test
    void khongProfileNaoDuocChonVaMoiTruongKhacDevThiThatBai() {
        // MockEnvironment không có profile active và profile mặc định là "default" (không phải dev)
        assertThatThrownBy(() -> build("", "", false, new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> build("", "", false, profiles("staging")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void prodBatCoChoPhepKhoaTam() {
        RSAKey key = build("", "", true, profiles("prod"));

        assertThat(key.isPrivate()).isTrue();
    }

    @Test
    void devTestDemoSeedGiuHanhViCuSinhKhoaTamKhongCanCo() {
        for (String profile : List.of("dev", "test", "demo-seed")) {
            assertThat(build("", "", false, profiles(profile)).isPrivate()).as(profile).isTrue();
        }
        // dev+demo-seed như lệnh chạy demo-seed thực tế
        assertThat(build("", "", false, profiles("dev", "demo-seed")).isPrivate()).isTrue();
    }

    @Test
    void coPemDayDuThiProdKhoiDongBinhThuongKhongCanCo() throws Exception {
        KeyPair pair = PemKeys.generate();
        String priv = PemKeys.toPem((RSAPrivateKey) pair.getPrivate());
        String pub = PemKeys.toPem((RSAPublicKey) pair.getPublic());

        RSAKey key = build(priv, pub, false, profiles("prod"));

        assertThat(key.toRSAPublicKey().getModulus()).isEqualTo(((RSAPublicKey) pair.getPublic()).getModulus());
    }

    @Test
    void chiCoMotTrongHaiPemVanLoiKeCaKhiBatCo() {
        KeyPair pair = PemKeys.generate();
        String priv = PemKeys.toPem((RSAPrivateKey) pair.getPrivate());

        assertThatThrownBy(() -> build(priv, "", true, profiles("dev"))).isInstanceOf(IllegalStateException.class);
    }
}
