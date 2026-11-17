package lms.customer.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Chạy {@link StartupConfigurationCheck} lúc khởi động: fail-fast ngoài profile dev, cảnh báo một lần ở dev. */
@Configuration
public class StartupChecksConfiguration implements InitializingBean {

    private final Environment environment;
    private final StorefrontProperties storefront;
    private final String jwkSetUri;

    public StartupChecksConfiguration(Environment environment, StorefrontProperties storefront,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") String jwkSetUri) {
        this.environment = environment;
        this.storefront = storefront;
        this.jwkSetUri = jwkSetUri;
    }

    @Override
    public void afterPropertiesSet() {
        StartupConfigurationCheck.validate(environment.acceptsProfiles(Profiles.of("dev")), storefront.clientSecret(),
                jwkSetUri);
    }
}
