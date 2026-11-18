package lms.identity.auth.infrastructure.config;

import lms.identity.auth.infrastructure.security.PemKeys;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
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

    /**
     * Khóa công khai CŨ còn nằm trong JWKS trong cửa sổ xoay khóa (SAAS_API_AUTHENTICATION_DESIGN 4.5). Chỉ để XÁC MINH
     * token đã ký bằng khóa cũ; không bao giờ dùng để ký. Bỏ trống = không có khóa cũ (hành vi như trước).
     */
    public record PreviousPublicKeys(List<RSAKey> keys) {

        public static final PreviousPublicKeys NONE = new PreviousPublicKeys(List.of());
    }

    @Bean
    public PreviousPublicKeys previousPublicKeys(@Value("${auth.jwt.previous-public-key-pem:}") String previousPem,
                                          RSAKey rsaKey) {
        if (previousPem == null || previousPem.isBlank()) return PreviousPublicKeys.NONE;
        return new PreviousPublicKeys(List.of(parsePrevious(previousPem, rsaKey)));
    }

    /** PEM sai hoặc trùng khóa đang ký thì lỗi khởi động rõ ràng (không bỏ qua im lặng để tránh mất khóa khi xoay). */
    public static RSAKey parsePrevious(String previousPem, RSAKey current) {
        RSAPublicKey previous;
        try {
            previous = PemKeys.parsePublic(previousPem);
        } catch (RuntimeException e) {
            throw new IllegalStateException("auth.jwt.previous-public-key-pem (AUTH_JWT_PREVIOUS_PUBLIC_KEY) "
                    + "không phải PEM khóa công khai X.509 RSA hợp lệ", e);
        }
        try {
            RSAKey key = new RSAKey.Builder(previous).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256)
                    .keyIDFromThumbprint().build();
            if (key.getKeyID().equals(current.getKeyID())) {
                throw new IllegalStateException("auth.jwt.previous-public-key-pem trùng khóa đang ký: bỏ trống nó "
                        + "hoặc đặt đúng khóa công khai CŨ");
            }
            return key;
        } catch (JOSEException e) {
            throw new IllegalStateException("Không dựng được JWK từ khóa công khai cũ", e);
        }
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey rsaKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));
    }

    /** Auth-service cũng là resource server cho các endpoint cần quyền (vd. tạo credential). */
    @Bean
    public JwtDecoder jwtDecoder(RSAKey rsaKey, PreviousPublicKeys previous) {
        try {
            if (previous.keys().isEmpty()) {
                return NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
            }
            // Cửa sổ xoay khóa: chọn khóa xác minh theo kid giữa khóa hiện tại và khóa cũ (như các resource server qua JWKS).
            List<com.nimbusds.jose.jwk.JWK> all = new java.util.ArrayList<>();
            all.add(rsaKey.toPublicJWK());
            all.addAll(previous.keys());
            DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
            processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256,
                    new ImmutableJWKSet<>(new JWKSet(all))));
            processor.setJWTClaimsSetVerifier((claims, context) -> { });
            return new NimbusJwtDecoder(processor);
        } catch (JOSEException e) {
            throw new IllegalStateException("Không dựng được JwtDecoder", e);
        }
    }
}
