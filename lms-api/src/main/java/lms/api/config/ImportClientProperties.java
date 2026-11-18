package lms.api.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Thời gian chờ riêng cho lời gọi nhập Excel sang service (app.imports.*, ADR-0024). Upload/xác nhận lâu hơn
 * lời gọi thường (5 s) nên dùng read-timeout 30 s; connect-timeout dùng chung với app.services.
 */
@ConfigurationProperties(prefix = "app.imports")
public record ImportClientProperties(Duration readTimeout) {

    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);

    public ImportClientProperties {
        if (readTimeout == null) {
            readTimeout = DEFAULT_READ_TIMEOUT;
        }
    }
}
