package lms.integration.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Chạy {@link StartupConfigurationCheck} lúc khởi động: fail-fast ngoài profile dev, cảnh báo ở dev. */
@Configuration
public class StartupChecksConfiguration implements InitializingBean {

    private final Environment environment;
    private final IntegrationProperties properties;
    private final String jwkSetUri;

    public StartupChecksConfiguration(Environment environment, IntegrationProperties properties,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") String jwkSetUri) {
        this.environment = environment;
        this.properties = properties;
        this.jwkSetUri = jwkSetUri;
    }

    @Override
    public void afterPropertiesSet() {
        StartupConfigurationCheck.validate(environment.acceptsProfiles(Profiles.of("dev")), jwkSetUri,
                properties.jwt().issuer());
    }
}
