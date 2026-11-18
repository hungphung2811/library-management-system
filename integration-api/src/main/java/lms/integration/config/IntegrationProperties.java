package lms.integration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Cấu hình app.integration.*: issuer JWT bắt buộc khớp identity. */
@ConfigurationProperties(prefix = "app.integration")
public record IntegrationProperties(Jwt jwt) {

    public static final String DEFAULT_ISSUER = "library-auth-service";

    @ConstructorBinding
    public IntegrationProperties {
        jwt = jwt == null ? new Jwt(DEFAULT_ISSUER) : jwt;
    }

    public record Jwt(String issuer) {
    }
}
