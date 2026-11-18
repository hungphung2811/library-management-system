package lms.integration.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Bộ giải mã JWT của integration-api: chữ ký theo JWKS của identity, hạn dùng, {@code iss} khớp cấu hình và BẮT BUỘC
 * {@code aud} chứa {@value #INTEGRATION_AUDIENCE}. Token nhân viên (không aud hoặc lms-staff), storefront
 * (lms-storefront) và token dịch vụ ({@code svc}) bị từ chối ngay ở decoder (401). Thêm một lớp phòng thủ: token mang
 * {@code svc}, {@code admin} hoặc {@code platformAdmin} cũng bị từ chối dù aud đúng (identity không bao giờ cấp tổ hợp đó).
 */
@Configuration
@EnableConfigurationProperties(IntegrationProperties.class)
public class IntegrationJwtConfiguration {

    public static final String INTEGRATION_AUDIENCE = "lms-integration";

    @Bean
    JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
                          IntegrationProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(validator(properties.jwt().issuer()));
        return decoder;
    }

    public static OAuth2TokenValidator<Jwt> validator(String issuer) {
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
                new IntegrationTokenValidator());
    }

    /** aud phải chứa lms-integration; không có claim đặc quyền nhân viên/dịch vụ. */
    static final class IntegrationTokenValidator implements OAuth2TokenValidator<Jwt> {

        private static final OAuth2Error ERROR = new OAuth2Error("invalid_token",
                "Token không dành cho integration API", null);
        private static final List<String> FORBIDDEN_CLAIMS = List.of("svc", "admin", "platformAdmin");

        @Override
        public OAuth2TokenValidatorResult validate(Jwt jwt) {
            List<String> audience = jwt.getAudience();
            boolean audienceOk = audience != null && audience.contains(INTEGRATION_AUDIENCE);
            boolean privileged = FORBIDDEN_CLAIMS.stream().anyMatch(jwt::hasClaim);
            return audienceOk && !privileged ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(ERROR);
        }
    }
}
