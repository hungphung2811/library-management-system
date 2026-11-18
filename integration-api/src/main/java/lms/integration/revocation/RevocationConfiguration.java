package lms.integration.revocation;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Bật kiểm thu hồi khi {@code app.integration.revocation.enabled=true}; tắt thì không có bean (không chạm Redis). */
@Configuration
@ConditionalOnProperty(prefix = "app.integration.revocation", name = "enabled", havingValue = "true")
public class RevocationConfiguration {

    @Bean
    TokenRevocationChecker tokenRevocationChecker(StringRedisTemplate redis) {
        return new RedisTokenRevocationChecker(redis, Clock.systemUTC());
    }
}
