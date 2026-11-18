package lms.integration.revocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.oauth2.jwt.Jwt;

/** Denylist theo jti, dấu thu hồi theo application (so với iat), fail-open khi Redis lỗi. */
@ExtendWith(OutputCaptureExtension.class)
class RedisTokenRevocationCheckerTest {

    private static final UUID APP = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final String JTI = "11111111-2222-3333-4444-555555555555";
    private static final Instant IAT = Instant.parse("2026-11-18T10:00:00Z");

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final Clock clock = Clock.fixed(IAT.plusSeconds(60), ZoneOffset.UTC);
    private final RedisTokenRevocationChecker checker = new RedisTokenRevocationChecker(redis, clock);

    RedisTokenRevocationCheckerTest() {
        when(redis.opsForValue()).thenReturn(ops);
    }

    private static Jwt jwt(String jti, String application, Instant iat) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").issuedAt(iat).expiresAt(iat.plusSeconds(300));
        if (jti != null) b.claim("jti", jti);
        if (application != null) b.claim("application_id", application);
        return b.build();
    }

    @Test
    void khongCoDauThiKhongThuHoi() {
        when(ops.multiGet(anyList())).thenReturn(Arrays.asList(null, null));
        assertThat(checker.isRevoked(jwt(JTI, APP.toString(), IAT))).isFalse();
    }

    @Test
    void jtiTrongDenylistThiThuHoi() {
        when(ops.multiGet(anyList())).thenReturn(Arrays.asList("1", null));
        assertThat(checker.isRevoked(jwt(JTI, APP.toString(), IAT))).isTrue();
    }

    @Test
    void khoaRedisDungMauVaChiChuaUuidHoacJtiAnToan() {
        when(ops.multiGet(anyList())).thenReturn(Arrays.asList(null, null));
        checker.isRevoked(jwt(JTI, APP.toString(), IAT));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(ops).multiGet(keys.capture());
        assertThat(keys.getValue()).containsExactly("lms:revoked:v1:jti:" + JTI, "lms:revoked:v1:app:" + APP);
    }

    @Test
    void jtiLaBiBoQuaKhongChenVaoKhoa() {
        when(ops.multiGet(anyList())).thenReturn(Arrays.asList("x", null));
        // jti chứa ký tự lạ: không dùng cho khóa, nên giá trị ở vị trí jti không thể làm thu hồi
        assertThat(checker.isRevoked(jwt("a:b*", APP.toString(), IAT))).isFalse();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(ops).multiGet(keys.capture());
        assertThat(keys.getValue().get(0)).isEqualTo("lms:revoked:v1:jti:-");
    }

    @Test
    void tokenCapTruocMocThuHoiApplicationThiBiTuChoi() {
        long marker = IAT.getEpochSecond() + 1;
        when(ops.multiGet(anyList())).thenReturn(Arrays.asList(null, String.valueOf(marker)));
        assertThat(checker.isRevoked(jwt(JTI, APP.toString(), IAT))).isTrue();
    }

    @Test
    void tokenCapSauMocThuHoiThiConDung() {
        long marker = IAT.getEpochSecond() - 30;
        when(ops.multiGet(anyList())).thenReturn(Arrays.asList(null, String.valueOf(marker)));
        assertThat(checker.isRevoked(jwt(JTI, APP.toString(), IAT))).isFalse();
    }

    @Test
    void dauKhongPhaiSoThiBoQua() {
        when(ops.multiGet(anyList())).thenReturn(Arrays.asList(null, "abc"));
        assertThat(checker.isRevoked(jwt(JTI, APP.toString(), IAT))).isFalse();
    }

    @Test
    void redisLoiThiFailOpenLogMotLanVaBoQuaRedisTamThoi(CapturedOutput output) {
        when(ops.multiGet(anyList())).thenThrow(new RedisConnectionFailureException("Redis chết"));
        for (int i = 0; i < 3; i++) assertThat(checker.isRevoked(jwt(JTI, APP.toString(), IAT))).isFalse();
        verify(ops, times(1)).multiGet(anyList());
        assertThat(output.getAll().lines().filter(l -> l.contains("fail-open"))).hasSize(1);
    }

    @Test
    void phanHoiRongThiFailOpen() {
        when(ops.multiGet(anyList())).thenReturn(null);
        assertThat(checker.isRevoked(jwt(JTI, APP.toString(), IAT))).isFalse();
    }
}
