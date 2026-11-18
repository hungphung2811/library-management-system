package lms.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;

class RequestCacheAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RequestCacheAutoConfiguration.class))
            .withBean(com.fasterxml.jackson.databind.ObjectMapper.class);

    @Test
    void coRedisVaMacDinhBatThiDungRedis() {
        runner.withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class)).run(ctx -> {
            assertThat(ctx).hasSingleBean(RequestCache.class);
            assertThat(ctx.getBean(RequestCache.class)).isInstanceOf(RedisRequestCache.class);
            assertThat(ctx.getBean(RequestCache.class).isEnabled()).isTrue();
        });
    }

    @Test
    void thuocTinhTatThiNoOp() {
        runner.withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withPropertyValues("lms.cache.enabled=false").run(ctx -> {
                    assertThat(ctx.getBean(RequestCache.class)).isInstanceOf(NoOpRequestCache.class);
                    assertThat(ctx.getBean(RequestCache.class).isEnabled()).isFalse();
                });
    }

    @Test
    void bienMoiTruongLmsCacheEnabledFalseCungTatCache() {
        // Spring ánh xạ LMS_CACHE_ENABLED trong biến môi trường hệ điều hành sang lms.cache.enabled
        runner.withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addLast(
                        new SystemEnvironmentPropertySource("systemEnvironment", Map.of("LMS_CACHE_ENABLED", "false"))))
                .run(ctx -> assertThat(ctx.getBean(RequestCache.class)).isInstanceOf(NoOpRequestCache.class));
    }

    @Test
    void khongCoRedisThiNoOp() {
        runner.run(ctx -> assertThat(ctx.getBean(RequestCache.class)).isInstanceOf(NoOpRequestCache.class));
    }

    @Test
    void noOpChayThangLoader() {
        NoOpRequestCache cache = new NoOpRequestCache();
        assertThat(cache.get("ns", "k", java.time.Duration.ofMinutes(1), String.class, () -> "v")).isEqualTo("v");
        cache.evict("ns", "k");
        cache.evictNamespace("ns");
    }
}
