package lms.identity.auth.infrastructure.config;

import lms.identity.auth.infrastructure.security.PemKeys;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Khóa RS256: đọc PEM từ cấu hình (prod). Bỏ trống thì sinh khóa tạm ở profile dev/test/demo-seed hoặc khi bật
 * {@code auth.jwt.allow-ephemeral-key}; ngoài ra khởi động thất bại.
 */
@Configuration
public class JwtKeyConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyConfiguration.class);

    /** Profile được dùng khóa tạm mà không cần cờ {@code auth.jwt.allow-ephemeral-key}. */
    private static final String[] EPHEMERAL_KEY_PROFILES = {"dev", "test", "demo-seed"};

    @Bean
    public RSAKey rsaKey(@Value("${auth.jwt.private-key-pem:}") String privatePem,
                  @Value("${auth.jwt.public-key-pem:}") String publicPem,
                  @Value("${auth.jwt.allow-ephemeral-key:false}") boolean allowEphemeralKey,
                  Environment environment) {
        RSAPrivateKey privateKey;
        RSAPublicKey publicKey;
        if (privatePem.isBlank() && publicPem.isBlank()) {
            // Ngoài dev/test/demo-seed, khóa tạm làm token mất hiệu lực mỗi lần khởi động và không dùng chung được giữa
            // các bản chạy: từ chối trừ khi cờ tường minh bật (compose demo)
            if (!allowEphemeralKey && !environment.acceptsProfiles(Profiles.of(EPHEMERAL_KEY_PROFILES))) {
                throw new IllegalStateException("Thiếu khóa ký JWT: đặt AUTH_JWT_PRIVATE_KEY và AUTH_JWT_PUBLIC_KEY (PEM). "
                        + "Chỉ khi chạy demo mới đặt AUTH_JWT_ALLOW_EPHEMERAL_KEY=true để dùng khóa tạm "
                        + "(token mất hiệu lực khi khởi động lại)");
            }
            log.warn("Chưa cấu hình auth.jwt.*-key-pem: dùng khóa RSA tạm, token mất hiệu lực khi khởi động lại");
            KeyPair pair = PemKeys.generate();
            privateKey = (RSAPrivateKey) pair.getPrivate();
            publicKey = (RSAPublicKey) pair.getPublic();
        } else if (privatePem.isBlank() || publicPem.isBlank()) {
            throw new IllegalStateException("Phải cấu hình cả private-key-pem và public-key-pem, hoặc bỏ trống cả hai");
        } else {
            privateKey = PemKeys.parsePrivate(privatePem);
            publicKey = PemKeys.parsePublic(publicPem);
        }
        try {
            return new RSAKey.Builder(publicKey).privateKey(privateKey)
                    .keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).keyIDFromThumbprint().build();
        } catch (JOSEException e) {
            throw new IllegalStateException("Không dựng được JWK từ khóa RSA", e);
        }
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey rsaKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));
    }

    /** Auth-service cũng là resource server cho các endpoint cần quyền (vd. tạo credential). */
    @Bean
    JwtDecoder jwtDecoder(RSAKey rsaKey) {
        try {
            return NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
        } catch (JOSEException e) {
            throw new IllegalStateException("Không dựng được JwtDecoder", e);
        }
    }
}
