package lms.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisRateLimiterTest {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final AtomicLong millis = new AtomicLong(60_000L * 1000);
    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private RedisRateLimiter limiter;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(ops);
        var props = new CacheProperties(true, "lms", "1", Duration.ofSeconds(5), 1000);
        limiter = new RedisRateLimiter(redis, props, null, millis::get, nanos::get);
        TenantContext.set(TENANT_A);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void duoiHanMucThiChoQuaVaVuotThiChan() {
        when(ops.increment(anyString())).thenReturn(1L, 2L, 3L, 4L);

        assertThat(limiter.tryAcquire("order.create", "u1", 3, MINUTE)).isTrue();
        assertThat(limiter.tryAcquire("order.create", "u1", 3, MINUTE)).isTrue();
        assertThat(limiter.tryAcquire("order.create", "u1", 3, MINUTE)).isTrue();
        assertThat(limiter.tryAcquire("order.create", "u1", 3, MINUTE)).isFalse();
    }

    @Test
    void chiDatHanSongOLanDauCuaCuaSo() {
        when(ops.increment(anyString())).thenReturn(1L, 2L);

        limiter.tryAcquire("order.create", "u1", 5, MINUTE);
        limiter.tryAcquire("order.create", "u1", 5, MINUTE);

        verify(redis, times(1)).expire(anyString(), eq(Duration.ofMillis(61_000)));
    }

    @Test
    void khoaGanTenantNenTenantKhacKhongAnHanMuc() {
        when(ops.increment(anyString())).thenReturn(1L);

        limiter.tryAcquire("order.create", "u1", 5, MINUTE);
        TenantContext.set(TENANT_B);
        limiter.tryAcquire("order.create", "u1", 5, MINUTE);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(ops, times(2)).increment(keys.capture());
        assertThat(keys.getAllValues().get(0)).startsWith("lms:v1:rl:t:" + TENANT_A + ":order.create:");
        assertThat(keys.getAllValues().get(1)).startsWith("lms:v1:rl:t:" + TENANT_B + ":order.create:");
        assertThat(keys.getAllValues().get(0)).isNotEqualTo(keys.getAllValues().get(1));
    }

    @Test
    void doiTuongKhacNhauDemRieng() {
        when(ops.increment(anyString())).thenReturn(1L);

        limiter.tryAcquire("order.create", "u1", 5, MINUTE);
        limiter.tryAcquire("order.create", "u2", 5, MINUTE);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(ops, times(2)).increment(keys.capture());
        assertThat(keys.getAllValues().get(0)).isNotEqualTo(keys.getAllValues().get(1));
    }

    @Test
    void sangCuaSoMoiThiDungKhoaMoi() {
        when(ops.increment(anyString())).thenReturn(1L);

        limiter.tryAcquire("order.create", "u1", 5, MINUTE);
        millis.addAndGet(61_000);
        limiter.tryAcquire("order.create", "u1", 5, MINUTE);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(ops, times(2)).increment(keys.capture());
        assertThat(keys.getAllValues().get(0)).isNotEqualTo(keys.getAllValues().get(1));
    }

    @Test
    void redisLoiThiChoQuaVaTamBoQuaRedis() {
        when(ops.increment(anyString())).thenThrow(new RuntimeException("Redis chết"));

        assertThat(limiter.tryAcquire("order.create", "u1", 1, MINUTE)).isTrue();
        assertThat(limiter.tryAcquire("order.create", "u1", 1, MINUTE)).isTrue();

        verify(ops, times(1)).increment(anyString()); // lần 2 nằm trong thời gian lùi
        nanos.addAndGet(Duration.ofSeconds(6).toNanos());
        limiter.tryAcquire("order.create", "u1", 1, MINUTE);
        verify(ops, times(2)).increment(anyString());
    }

    @Test
    void thamSoKhongHopLeBiTuChoi() {
        assertThatThrownBy(() -> limiter.tryAcquire("a:b", "u", 1, MINUTE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter.tryAcquire("ok", "u", 0, MINUTE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter.tryAcquire("ok", "u", 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        verify(ops, never()).increment(anyString());
    }
}
