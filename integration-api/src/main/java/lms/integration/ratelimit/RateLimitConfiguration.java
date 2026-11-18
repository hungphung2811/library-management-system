package lms.integration.ratelimit;

import java.time.Clock;
import lms.integration.config.IntegrationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Bật bộ giới hạn Redis khi {@code app.integration.rate-limit.enabled=true}; tắt thì không có bean (không chạm Redis). */
@Configuration
@ConditionalOnProperty(prefix = "app.integration.rate-limit", name = "enabled", havingValue = "true")
public class RateLimitConfiguration {

    @Bean
    ApiRateLimiter apiRateLimiter(StringRedisTemplate redis, IntegrationProperties properties) {
        return new RedisApiRateLimiter(redis, Clock.systemUTC(), properties.rateLimit().defaultPerMinute());
    }
}
