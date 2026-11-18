package lms.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Bean {@link RequestCache}: Redis khi có {@code StringRedisTemplate} và cache đang bật; ngược lại no-op (luôn chạy thẳng
 * loader). Tắt cache bằng biến môi trường {@code LMS_CACHE_ENABLED=false} (Spring ánh xạ sang {@code lms.cache.enabled}).
 */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration")
@EnableConfigurationProperties(CacheProperties.class)
public class RequestCacheAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StringRedisTemplate.class)
    @ConditionalOnProperty(prefix = "lms.cache", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class RedisCacheConfiguration {

        @Bean
        @ConditionalOnBean(StringRedisTemplate.class)
        @ConditionalOnMissingBean(RequestCache.class)
        RequestCache redisRequestCache(StringRedisTemplate redis, ObjectMapper mapper, CacheProperties properties,
                                       ObjectProvider<MeterRegistry> meters) {
            return new RedisRequestCache(redis, mapper, properties, meters.getIfAvailable());
        }
    }

    @Bean
    @ConditionalOnMissingBean(RequestCache.class)
    RequestCache noOpRequestCache() {
        return new NoOpRequestCache();
    }

    /**
     * Giới hạn tốc độ có công tắc RIÊNG ({@code lms.ratelimit.enabled}, mặc định bật) vì đây là biện pháp bảo mật, không
     * nên tắt theo cache của dev/demo. Cần Redis; không có Redis hoặc tắt thì cho qua hết.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StringRedisTemplate.class)
    @ConditionalOnProperty(prefix = "lms.ratelimit", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class RedisRateLimitConfiguration {

        @Bean
        @ConditionalOnBean(StringRedisTemplate.class)
        @ConditionalOnMissingBean(RateLimiter.class)
        RateLimiter redisRateLimiter(StringRedisTemplate redis, CacheProperties properties,
                                     ObjectProvider<MeterRegistry> meters) {
            return new RedisRateLimiter(redis, properties, meters.getIfAvailable());
        }
    }

    @Bean
    @ConditionalOnMissingBean(RateLimiter.class)
    RateLimiter noOpRateLimiter() {
        return new RateLimiter() {
            @Override
            public boolean tryAcquire(String bucket, String subject, int limit, java.time.Duration window) {
                return true;
            }

            @Override
            public boolean isEnabled() {
                return false;
            }
        };
    }
}
