package lms.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisRequestCacheTest {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final TypeReference<List<String>> LIST = new TypeReference<>() { };

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final AtomicInteger loads = new AtomicInteger();
    private RedisRequestCache cache;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(ops);
        CacheProperties props = new CacheProperties(true, "lms", "1", Duration.ofSeconds(5), 1000);
        cache = new RedisRequestCache(redis, new ObjectMapper(), props, meters, clock::get);
        TenantContext.set(TENANT_A);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private List<String> load() {
        loads.incrementAndGet();
        return List.of("a", "b");
    }

    private double count(String result) {
        var counter = meters.find("lms.cache.requests").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void trungCacheThiKhongGoiLoader() {
        when(ops.get(anyString())).thenReturn("[\"x\",\"y\"]");

        List<String> result = cache.get("books", "k1", TTL, LIST, this::load);

        assertThat(result).containsExactly("x", "y");
        assertThat(loads).hasValue(0);
        assertThat(count("hit")).isEqualTo(1);
    }

    @Test
    void truotCacheGoiLoaderVaGhiVoiTtl() {
        when(ops.get(anyString())).thenReturn(null);

        List<String> result = cache.get("books", "k1", TTL, LIST, this::load);

        assertThat(result).containsExactly("a", "b");
        assertThat(loads).hasValue(1);
        verify(ops).set(anyString(), eq("[\"a\",\"b\"]"), eq(TTL));
        assertThat(count("miss")).isEqualTo(1);
    }

    @Test
    void khoaGanTenantVaKhacNhauGiuaCacTenant() {
        when(ops.get(anyString())).thenReturn(null);
        cache.get("books", "k1", TTL, LIST, this::load);
        TenantContext.set(TENANT_B);
        cache.get("books", "k1", TTL, LIST, this::load);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(ops, org.mockito.Mockito.times(2)).set(keys.capture(), anyString(), any(Duration.class));
        assertThat(keys.getAllValues().get(0)).startsWith("lms:v1:t:" + TENANT_A + ":books:");
        assertThat(keys.getAllValues().get(1)).startsWith("lms:v1:t:" + TENANT_B + ":books:");
        assertThat(keys.getAllValues().get(0)).isNotEqualTo(keys.getAllValues().get(1));
    }

    @Test
    void khoaKhacNhauKhiDauVaoKhacNhau() {
        when(ops.get(anyString())).thenReturn(null);
        cache.get("books", "k1", TTL, LIST, this::load);
        cache.get("books", "k2", TTL, LIST, this::load);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(ops, org.mockito.Mockito.times(2)).set(keys.capture(), anyString(), any(Duration.class));
        assertThat(keys.getAllValues().get(0)).isNotEqualTo(keys.getAllValues().get(1));
    }

    @Test
    void khongCoTenantHoacDocMoiTenantThiKhongCache() {
        TenantContext.clear();
        cache.get("books", "k", TTL, LIST, this::load);
        TenantContext.bindAllTenants();
        cache.get("books", "k", TTL, LIST, this::load);

        assertThat(loads).hasValue(2);
        verify(ops, never()).get(anyString());
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
        assertThat(count("bypass")).isEqualTo(2);
    }

    @Test
    void getGlobalKhongCanTenantVaDungKhoaG() {
        TenantContext.clear();
        when(ops.get(anyString())).thenReturn(null);

        cache.getGlobal("catalog.refs", "all", TTL, LIST, this::load);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(ops).set(key.capture(), anyString(), any(Duration.class));
        assertThat(key.getValue()).startsWith("lms:v1:g:catalog.refs:");
    }

    @Test
    void ketQuaNullKhongDuocCache() {
        when(ops.get(anyString())).thenReturn(null);

        String result = cache.get("books", "k", TTL, String.class, () -> null);

        assertThat(result).isNull();
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void loaderNemLoiThiNemNguyenVaKhongGhiCache() {
        when(ops.get(anyString())).thenReturn(null);

        assertThatThrownBy(() -> cache.get("books", "k", TTL, LIST, () -> {
            throw new IllegalStateException("db lỗi");
        })).isInstanceOf(IllegalStateException.class).hasMessage("db lỗi");

        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void ttlKhongHopLeThiChayThangLoader() {
        cache.get("books", "k", Duration.ZERO, LIST, this::load);
        cache.get("books", "k", null, LIST, this::load);

        assertThat(loads).hasValue(2);
        verify(ops, never()).get(anyString());
    }

    @Test
    void redisLoiThiRoiVeLoaderVaTamBoQuaRedis() {
        when(ops.get(anyString())).thenThrow(new RuntimeException("Redis chết"));

        List<String> first = cache.get("books", "k", TTL, LIST, this::load);
        List<String> second = cache.get("books", "k", TTL, LIST, this::load);

        assertThat(first).containsExactly("a", "b");
        assertThat(second).containsExactly("a", "b");
        assertThat(loads).hasValue(2);
        // Lần 2 nằm trong thời gian lùi nên không chạm Redis nữa
        verify(ops, org.mockito.Mockito.times(1)).get(anyString());
        assertThat(count("error")).isEqualTo(1);
    }

    @Test
    void hetThoiGianLuiThiThuLaiRedis() {
        when(ops.get(anyString())).thenThrow(new RuntimeException("Redis chết")).thenReturn("[\"x\"]");
        cache.get("books", "k", TTL, LIST, this::load);

        clock.addAndGet(Duration.ofSeconds(6).toNanos());
        List<String> result = cache.get("books", "k", TTL, LIST, this::load);

        assertThat(result).containsExactly("x");
        assertThat(loads).hasValue(1);
    }

    @Test
    void duLieuCacheHongThiBoQuaVaGhiDe() {
        when(ops.get(anyString())).thenReturn("{không phải json");

        List<String> result = cache.get("books", "k", TTL, LIST, this::load);

        assertThat(result).containsExactly("a", "b");
        assertThat(loads).hasValue(1);
    }

    @Test
    void giaTriQuaLonKhongGhiCache() {
        when(ops.get(anyString())).thenReturn(null);

        cache.get("books", "k", TTL, String.class, () -> "x".repeat(2000));

        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void namespaceKhongHopLeBiTuChoi() {
        assertThatThrownBy(() -> cache.get("a:b", "k", TTL, LIST, this::load)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cache.get("a*", "k", TTL, LIST, this::load)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cache.evictNamespace("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void evictXoaDungKhoaCuaTenantHienTai() {
        when(ops.get(anyString())).thenReturn(null);
        cache.get("books", "k1", TTL, LIST, this::load);
        ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
        verify(ops).set(written.capture(), anyString(), any(Duration.class));

        cache.evict("books", "k1");

        verify(redis).delete(written.getValue());
    }

    @Test
    void evictGlobalXoaKhoaGKhongCanTenant() {
        TenantContext.clear();
        when(ops.get(anyString())).thenReturn(null);
        cache.getGlobal("cms.pages", "all", TTL, LIST, this::load);
        ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
        verify(ops).set(written.capture(), anyString(), any(Duration.class));

        cache.evictGlobal("cms.pages", "all");

        verify(redis).delete(written.getValue());
    }

    @Test
    void evictKhongCoTenantThiKhongLamGi() {
        TenantContext.clear();

        cache.evict("books", "k1");
        cache.evictNamespace("books");

        verify(redis, never()).delete(anyString());
    }
}
