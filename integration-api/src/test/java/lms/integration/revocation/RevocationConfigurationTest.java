package lms.integration.revocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Bật/tắt kiểm thu hồi bằng app.integration.revocation.enabled (mặc định tắt, không chạm Redis). */
class RevocationConfigurationTest {

    @Configuration
    static class Base {
        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Base.class, RevocationConfiguration.class);

    @Test
    void matDinhTatKhongCoBean() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(TokenRevocationChecker.class));
        runner.withPropertyValues("app.integration.revocation.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(TokenRevocationChecker.class));
    }

    @Test
    void batThiCoBean() {
        runner.withPropertyValues("app.integration.revocation.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(TokenRevocationChecker.class));
    }
}
