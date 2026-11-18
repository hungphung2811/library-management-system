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
import java.util.List;
import java.util.UUID;
import lms.identity.auth.infrastructure.config.JwtKeyConfiguration;
import lms.identity.auth.infrastructure.config.JwtKeyConfiguration.PreviousPublicKeys;
import lms.identity.auth.infrastructure.security.JwtTokenIssuer;
import lms.identity.auth.infrastructure.security.PemKeys;
import lms.identity.auth.infrastructure.web.JwksController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Xoay khóa ký (SAAS_API_AUTHENTICATION_DESIGN 4.5): JWKS có thêm khóa công khai CŨ (kid riêng, không lộ phần riêng),
 * PEM sai hoặc trùng khóa đang ký thì lỗi khởi động rõ ràng, decoder của identity xác minh được token của cả hai khóa.
 */
class JwtKeyRotationTest {

    private final JwtKeyConfiguration config = new JwtKeyConfiguration();

    private static RSAKey newKey() {
        try {
            KeyPair pair = PemKeys.generate();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE)
                    .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256).keyIDFromThumbprint().build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String publicPem(RSAKey key) throws Exception {
        return PemKeys.toPem(key.toRSAPublicKey());
    }

    private static String tokenSignedBy(RSAKey key) {
        JwtTokenIssuer issuer = new JwtTokenIssuer(new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key))),
                key.getKeyID(), "library-auth-service", Duration.ofMinutes(15), Clock.systemUTC());
        return issuer.issueClient(new lms.identity.auth.application.ClientTokenIssuerPort.ClientClaims(UUID.randomUUID(), UUID.randomUUID(),
                List.of("books:read"), List.of("SALES"), Duration.ofMinutes(5))).value();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<PreviousPublicKeys> provider(PreviousPublicKeys keys) {
        ObjectProvider<PreviousPublicKeys> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(keys);
        return provider;
    }

    @Test
    void khongCoKhoaCuThiBeanRongVaJwksMotKhoaNhuCu() {
        RSAKey current = newKey();
        for (String blank : new String[]{"", "   ", null}) {
            assertThat(config.previousPublicKeys(blank, current).keys()).isEmpty();
        }
        var json = new JwksController(current, provider(PreviousPublicKeys.NONE)).jwks();
        assertThat((List<?>) json.get("keys")).hasSize(1);
    }

    @Test
    void jwksCoHaiKhoaKidRiengKhoaDangKyTruocVaKhongLoPhanRiengTu() throws Exception {
        RSAKey current = newKey();
        RSAKey old = newKey();
        PreviousPublicKeys previous = config.previousPublicKeys(publicPem(old), current);

        var json = new JwksController(current, provider(previous)).jwks();

        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> keys = (List<java.util.Map<String, Object>>) json.get("keys");
        assertThat(keys).hasSize(2);
        assertThat(keys.get(0).get("kid")).isEqualTo(current.getKeyID());
        assertThat(keys.get(1).get("kid")).isEqualTo(old.getKeyID()).isNotEqualTo(current.getKeyID());
        for (var key : keys) {
            for (String priv : new String[]{"d", "p", "q", "dp", "dq", "qi"}) {
                assertThat(key).as("lộ thành phần riêng tư " + priv).doesNotContainKey(priv);
            }
        }
    }

    @Test
    void pemSaiKhoiDongThatBaiVoiThongDiepRo() {
        RSAKey current = newKey();
        assertThatThrownBy(() -> config.previousPublicKeys("khong-phai-pem!!", current))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("AUTH_JWT_PREVIOUS_PUBLIC_KEY");
        assertThatThrownBy(() -> config.previousPublicKeys(PemKeys.toPem((RSAPrivateKey) PemKeys.generate().getPrivate()),
                current)).isInstanceOf(IllegalStateException.class).hasMessageContaining("previous-public-key-pem");
    }

    @Test
    void khoaCuTrungKhoaDangKyThiTuChoi() throws Exception {
        RSAKey current = newKey();
        assertThatThrownBy(() -> config.previousPublicKeys(publicPem(current), current))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("trùng khóa đang ký");
    }

    @Test
    void decoderCuaIdentityXacMinhTokenCuaCaHaiKhoaVaTuChoiKhoaLa() throws Exception {
        RSAKey current = newKey();
        RSAKey old = newKey();
        RSAKey stranger = newKey();
        JwtDecoder decoder = config.jwtDecoder(current, config.previousPublicKeys(publicPem(old), current));

        assertThat(decoder.decode(tokenSignedBy(current)).getClaimAsString("scope")).isEqualTo("books:read");
        assertThat(decoder.decode(tokenSignedBy(old)).getClaimAsString("scope")).isEqualTo("books:read");
        assertThatThrownBy(() -> decoder.decode(tokenSignedBy(stranger))).isInstanceOf(JwtException.class);
    }

    @Test
    void khongCoKhoaCuThiDecoderChiNhanKhoaHienTai() {
        RSAKey current = newKey();
        RSAKey old = newKey();
        JwtDecoder decoder = config.jwtDecoder(current, PreviousPublicKeys.NONE);

        assertThat(decoder.decode(tokenSignedBy(current)).getId()).isNotBlank();
        assertThatThrownBy(() -> decoder.decode(tokenSignedBy(old))).isInstanceOf(JwtException.class);
    }
}
