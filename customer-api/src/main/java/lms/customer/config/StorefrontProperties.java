package lms.customer.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** app.storefront.*: bí mật gọi identity và tham số bộ đệm tra tenant theo code (ADR-0011 2.2). */
@ConfigurationProperties(prefix = "app.storefront")
public record StorefrontProperties(String clientSecret, int cacheMaxSize, Duration negativeTtl, Duration refreshMargin,
                                   Duration originTtl, Boolean refreshTenantIdEnabled) {

    public StorefrontProperties {
        clientSecret = clientSecret == null ? "" : clientSecret;
        cacheMaxSize = cacheMaxSize <= 0 ? 10_000 : cacheMaxSize;
        negativeTtl = negativeTtl == null ? Duration.ofSeconds(60) : negativeTtl;
        refreshMargin = refreshMargin == null ? Duration.ofSeconds(60) : refreshMargin;
        // ADR-0013 2.3: trần TTL mục cache để origin đổi có hiệu lực trong <= 60 giây.
        originTtl = originTtl == null || originTtl.isZero() || originTtl.isNegative() ? Duration.ofSeconds(60) : originTtl;
        // Gửi tenantId kèm refresh ở /public/t/{code}/auth/refresh. Tắt chỉ khi identity cũ từ chối trường lạ.
        refreshTenantIdEnabled = refreshTenantIdEnabled == null || refreshTenantIdEnabled;
    }

    @Override
    public String toString() {
        return "StorefrontProperties[clientSecret=***]";
    }
}
