package lms.api.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Địa chỉ và thời gian chờ khi gọi các service nội bộ (app.services.*). */
@ConfigurationProperties(prefix = "app.services")
public record ServicesProperties(String identityUrl, String catalogUrl, String circulationUrl, String supportUrl, String systemUrl,
                                 Duration connectTimeout, Duration readTimeout) {
}
