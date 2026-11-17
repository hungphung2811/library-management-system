package lms.customer.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** app.cms.*: bộ đệm đọc trang giới thiệu (system-service render HTML, customer-api đệm 60 giây). */
@ConfigurationProperties(prefix = "app.cms")
public record CmsProperties(Duration cacheTtl, int cacheMaxSize) {

    public CmsProperties {
        cacheTtl = cacheTtl == null ? Duration.ofSeconds(60) : cacheTtl;
        cacheMaxSize = cacheMaxSize <= 0 ? 500 : cacheMaxSize;
    }
}
