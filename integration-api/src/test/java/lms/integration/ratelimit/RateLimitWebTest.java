package lms.integration.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.List;
import lms.integration.common.dto.PageView;
import lms.integration.config.SecurityConfiguration;
import lms.integration.config.TestTokens;
import lms.integration.inventory.client.InventoryClient;
import lms.integration.inventory.web.InventoryController;
import lms.integration.web.IntegrationExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Bộ giới hạn Redis THẬT (RedisApiRateLimiter) với Redis ném lỗi: request vẫn 200 (fail-open), không có header hạn mức. */
@ExtendWith(OutputCaptureExtension.class)
@WebMvcTest(InventoryController.class)
@Import({SecurityConfiguration.class, IntegrationExceptionHandler.class, RateLimitWebTest.Beans.class})
class RateLimitWebTest {

    static final TestTokens TOKENS = new TestTokens();

    @TestConfiguration
    static class Beans {
        @Bean
        JwtDecoder jwtDecoder() {
            return TOKENS.decoder();
        }

        @Bean
        ApiRateLimiter apiRateLimiter() {
            StringRedisTemplate redis = mock(StringRedisTemplate.class, withSettings().defaultAnswer(i -> {
                throw new RedisConnectionFailureException("Redis chết");
            }));
            return new RedisApiRateLimiter(redis, Clock.systemUTC(), 5);
        }
    }

    @Autowired MockMvc mvc;
    @MockitoBean InventoryClient inventory;

    @Test
    void redisNemLoiThiRequestVanThanhCong(CapturedOutput output) throws Exception {
        when(inventory.list(false, 0, 20)).thenReturn(new PageView<>(List.of(), 0, 0, 20));
        var claims = TestTokens.claims();
        claims.put("scope", "inventory:read");
        String own = TOKENS.with(TestTokens.ISSUER, List.of("lms-integration"), java.time.Duration.ofMinutes(5), claims);
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/integration/v1/inventory").header("Authorization", "Bearer " + own))
                    .andExpect(status().isOk()).andExpect(header().doesNotExist("X-RateLimit-Limit"))
                    .andExpect(header().doesNotExist("Retry-After"));
        }
        assertThat(output.getAll().lines().filter(l -> l.contains("fail-open"))).hasSize(1);
    }
}
