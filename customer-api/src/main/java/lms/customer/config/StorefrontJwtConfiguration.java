package lms.customer.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
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
 * Bộ giải mã JWT của customer-api: chữ ký theo JWKS của identity và BẮT BUỘC {@code aud} chứa
 * {@value #STOREFRONT_AUDIENCE} (docs/api/token-audience.md). Token nhân viên (không có {@code aud} hoặc
 * {@code lms-staff}) và token dịch vụ không dùng được ở route cần đăng nhập. Route công khai không bị ảnh hưởng vì
 * header Authorization bị bỏ qua ở đó (xem SecurityConfiguration).
 */
@Configuration
public class StorefrontJwtConfiguration {

    public static final String STOREFRONT_AUDIENCE = "lms-storefront";

    @Bean
    JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(validator());
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> validator() {
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), new StorefrontAudienceValidator());
    }

    /** Token phải có {@code aud} chứa lms-storefront. */
    static final class StorefrontAudienceValidator implements OAuth2TokenValidator<Jwt> {

        private static final OAuth2Error ERROR = new OAuth2Error("invalid_token",
                "Token không dành cho storefront", null);

        @Override
        public OAuth2TokenValidatorResult validate(Jwt jwt) {
            List<String> audience = jwt.getAudience();
            return audience != null && audience.contains(STOREFRONT_AUDIENCE)
                    ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(ERROR);
        }
    }
}
