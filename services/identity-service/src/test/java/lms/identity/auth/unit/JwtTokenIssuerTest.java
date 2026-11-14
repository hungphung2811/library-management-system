package lms.identity.auth.unit;

import static org.assertj.core.api.Assertions.assertThat;

import lms.identity.auth.application.TokenIssuerPort;
import lms.identity.auth.infrastructure.security.JwtTokenIssuer;
import lms.identity.auth.infrastructure.security.PemKeys;
import lms.security.JwtClaims;
import lms.security.PermissionJwtConverter;
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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Kiểm chứng token auth-service phát hành đúng định dạng mà các resource server (security-common) đọc được. */
class JwtTokenIssuerTest {

    @Test
    void tokenRs256XacMinhDuocBangKhoaPublicVaChuaPermissions() throws Exception {
        KeyPair pair = PemKeys.generate();
        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate())
                .keyIDFromThumbprint().build();
        Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
        JwtTokenIssuer issuer = new JwtTokenIssuer(new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey))),
                rsaKey.getKeyID(), "library-auth-service", Duration.ofMinutes(15), clock);
        UUID userId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();

        var issued = issuer.issue(new TokenIssuerPort.Claims(userId, "alice", departmentId,
                List.of("LIBRARIAN"), List.of("BORROW_BOOK", "RETURN_BOOK")));
        Jwt jwt = NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build().decode(issued.value());

        assertThat(issued.expiresInSeconds()).isEqualTo(900);
        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString(JwtClaims.USERNAME)).isEqualTo("alice");
        assertThat(jwt.getClaimAsString(JwtClaims.DEPARTMENT_ID)).isEqualTo(departmentId.toString());
        assertThat(jwt.getHeaders()).containsEntry("kid", rsaKey.getKeyID());
        assertThat(PermissionJwtConverter.create().convert(jwt).getAuthorities())
                .extracting(GrantedAuthority::getAuthority).containsExactlyInAnyOrder("BORROW_BOOK", "RETURN_BOOK");
    }

    @Test
    void khoaPemDocGhiKhuHoi() {
        KeyPair pair = PemKeys.generate();

        RSAPrivateKey privateKey = PemKeys.parsePrivate(PemKeys.toPem((RSAPrivateKey) pair.getPrivate()));
        RSAPublicKey publicKey = PemKeys.parsePublic(PemKeys.toPem((RSAPublicKey) pair.getPublic()));

        assertThat(privateKey.getModulus()).isEqualTo(((RSAPrivateKey) pair.getPrivate()).getModulus());
        assertThat(publicKey.getPublicExponent()).isEqualTo(((RSAPublicKey) pair.getPublic()).getPublicExponent());
    }
}
