package lms.integration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Cấu hình app.integration.*: issuer JWT bắt buộc khớp identity; giới hạn tốc độ theo application (pha I3). */
@ConfigurationProperties(prefix = "app.integration")
public record IntegrationProperties(Jwt jwt, RateLimit rateLimit) {

    public static final String DEFAULT_ISSUER = "library-auth-service";

    @ConstructorBinding
    public IntegrationProperties {
        jwt = jwt == null ? new Jwt(DEFAULT_ISSUER) : jwt;
        rateLimit = rateLimit == null ? new RateLimit(false, RateLimit.DEFAULT_PER_MINUTE) : rateLimit;
    }

    public record Jwt(String issuer) {
    }

    /**
     * Cửa sổ cố định 60 giây theo (tenant, application, nhóm endpoint). {@code defaultPerMinute} áp cho mọi application
     * (hạn mức riêng theo application, cột rate_limit_per_minute của V32, chưa có trong token nên CHƯA dùng); thiếu hoặc
     * không dương thì dùng mặc định.
     */
    public record RateLimit(boolean enabled, int defaultPerMinute) {

        public static final int DEFAULT_PER_MINUTE = 600;

        public RateLimit {
            defaultPerMinute = defaultPerMinute <= 0 ? DEFAULT_PER_MINUTE : defaultPerMinute;
        }
    }
}
