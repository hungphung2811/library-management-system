package lms.integration.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import lms.integration.config.IntegrationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Bật/tắt bộ giới hạn bằng cấu hình app.integration.rate-limit và đọc hạn mức mặc định. */
class RateLimitConfigurationTest {

    @Configuration
    @EnableConfigurationProperties(IntegrationProperties.class)
    static class Base {
        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Base.class, RateLimitConfiguration.class);

    @Test
    void matDinhTatKhongCoBean() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(ApiRateLimiter.class));
        runner.withPropertyValues("app.integration.rate-limit.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ApiRateLimiter.class));
    }

    @Test
    void batThiCoBeanRedis() {
        runner.withPropertyValues("app.integration.rate-limit.enabled=true",
                "app.integration.rate-limit.default-per-minute=120")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(ApiRateLimiter.class);
                    assertThat(ctx.getBean(IntegrationProperties.class).rateLimit().defaultPerMinute()).isEqualTo(120);
                });
    }

    @Test
    void hanMucKhongDuongThiDungMacDinh() {
        runner.withPropertyValues("app.integration.rate-limit.default-per-minute=0")
                .run(ctx -> assertThat(ctx.getBean(IntegrationProperties.class).rateLimit().defaultPerMinute())
                        .isEqualTo(IntegrationProperties.RateLimit.DEFAULT_PER_MINUTE));
    }
}
