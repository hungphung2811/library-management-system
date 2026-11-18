package lms.integration.revocation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lms.integration.common.dto.PageView;
import lms.integration.config.SecurityConfiguration;
import lms.integration.config.TestTokens;
import lms.integration.inventory.client.InventoryClient;
import lms.integration.inventory.web.InventoryController;
import lms.integration.web.IntegrationExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Token bị thu hồi nhận 401 (thân như token không hợp lệ); đường dẫn khác không bị ảnh hưởng. */
@WebMvcTest(InventoryController.class)
@Import({SecurityConfiguration.class, IntegrationExceptionHandler.class, RevocationWebTest.Beans.class})
class RevocationWebTest {

    static final TestTokens TOKENS = new TestTokens();
    static final AtomicBoolean REVOKED = new AtomicBoolean();

    @TestConfiguration
    static class Beans {
        @Bean
        JwtDecoder jwtDecoder() {
            return TOKENS.decoder();
        }

        @Bean
        TokenRevocationChecker tokenRevocationChecker() {
            return jwt -> REVOKED.get();
        }
    }

    @Autowired MockMvc mvc;
    @MockitoBean InventoryClient inventory;

    private static String token() {
        var claims = TestTokens.claims();
        claims.put("scope", "inventory:read");
        return TOKENS.with(TestTokens.ISSUER, List.of("lms-integration"), java.time.Duration.ofMinutes(5), claims);
    }

    @Test
    void tokenBiThuHoiNhan401() throws Exception {
        REVOKED.set(true);
        mvc.perform(get("/api/integration/v1/inventory").header("Authorization", "Bearer " + token()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void tokenChuaThuHoiThiBinhThuong() throws Exception {
        REVOKED.set(false);
        when(inventory.list(false, 0, 20)).thenReturn(new PageView<>(List.of(), 0, 0, 20));
        mvc.perform(get("/api/integration/v1/inventory").header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk());
    }

    @Test
    void redisThatLoiThiFailOpen() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("Redis chết"));
        RedisTokenRevocationChecker real = new RedisTokenRevocationChecker(redis, Clock.systemUTC());
        var jwt = TOKENS.decoder().decode(token());
        org.assertj.core.api.Assertions.assertThat(real.isRevoked(jwt)).isFalse();
    }
}
