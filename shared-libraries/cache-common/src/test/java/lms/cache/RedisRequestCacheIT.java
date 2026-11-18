package lms.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** Chạy trên Redis thật: {@code -Dtest=RedisRequestCacheIT -DargLine=-Dapi.version=1.44} (cần Docker). */
class RedisRequestCacheIT {

    record Book(UUID id, String name, LocalDate published) { }

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final TypeReference<List<Book>> BOOKS = new TypeReference<>() { };

    private static GenericContainer<?> container;
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private RedisRequestCache cache;
    private final AtomicInteger loads = new AtomicInteger();

    @BeforeAll
    static void start() {
        container = new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);
        container.start();
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(container.getHost(), container.getMappedPort(6379)));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void stop() {
        factory.destroy();
        container.stop();
    }

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        cache = new RedisRequestCache(redis, mapper, new CacheProperties(true, "lms", "1", Duration.ofSeconds(5), 10000), null);
        TenantContext.set(TENANT_A);
        loads.set(0);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private List<Book> load() {
        loads.incrementAndGet();
        return List.of(new Book(UUID.randomUUID(), "Nhà giả kim", LocalDate.of(2020, 1, 2)));
    }

    @Test
    void lanHaiLayTuRedisVaGiuNguyenKieuDuLieu() {
        List<Book> first = cache.get("books", "q=a", Duration.ofSeconds(30), BOOKS, this::load);
        List<Book> second = cache.get("books", "q=a", Duration.ofSeconds(30), BOOKS, this::load);

        assertThat(loads).hasValue(1);
        assertThat(second).isEqualTo(first);
        assertThat(second.get(0).published()).isEqualTo(LocalDate.of(2020, 1, 2));
    }

    @Test
    void tenantKhacNhauKhongThayDuLieuCuaNhau() {
        cache.get("books", "q=a", Duration.ofSeconds(30), BOOKS, this::load);
        TenantContext.set(TENANT_B);
        cache.get("books", "q=a", Duration.ofSeconds(30), BOOKS, this::load);

        assertThat(loads).hasValue(2);
        assertThat(redis.keys("lms:v1:t:" + TENANT_A + ":books:*")).hasSize(1);
        assertThat(redis.keys("lms:v1:t:" + TENANT_B + ":books:*")).hasSize(1);
    }

    @Test
    void ttlHetHanThiTaiLai() throws InterruptedException {
        cache.get("books", "q=a", Duration.ofMillis(300), BOOKS, this::load);
        Thread.sleep(500);
        cache.get("books", "q=a", Duration.ofMillis(300), BOOKS, this::load);

        assertThat(loads).hasValue(2);
    }

    @Test
    void evictNamespaceChiXoaCuaTenantHienTaiVaNamespaceDo() {
        cache.get("books", "q=a", Duration.ofSeconds(30), BOOKS, this::load);
        cache.get("books", "q=b", Duration.ofSeconds(30), BOOKS, this::load);
        cache.get("authors", "all", Duration.ofSeconds(30), BOOKS, this::load);
        TenantContext.set(TENANT_B);
        cache.get("books", "q=a", Duration.ofSeconds(30), BOOKS, this::load);
        TenantContext.set(TENANT_A);

        cache.evictNamespace("books");

        assertThat(redis.keys("lms:v1:t:" + TENANT_A + ":books:*")).isEmpty();
        assertThat(redis.keys("lms:v1:t:" + TENANT_A + ":authors:*")).hasSize(1);
        assertThat(redis.keys("lms:v1:t:" + TENANT_B + ":books:*")).hasSize(1);
    }

    @Test
    void evictGlobalHoatDongKhiKhongCoTenantVaKhongDungDenCuaTenant() {
        cache.getGlobal("cms.pages", "all", Duration.ofSeconds(30), BOOKS, this::load);
        cache.getGlobal("cms.pages", "slug-1", Duration.ofSeconds(30), BOOKS, this::load);
        cache.get("books", "q=a", Duration.ofSeconds(30), BOOKS, this::load);
        TenantContext.clear(); // platform admin: không có tenant

        cache.evictGlobal("cms.pages", "all");
        assertThat(redis.keys("lms:v1:g:cms.pages:*")).hasSize(1);

        cache.evictGlobalNamespace("cms.pages");
        assertThat(redis.keys("lms:v1:g:cms.pages:*")).isEmpty();
        assertThat(redis.keys("lms:v1:t:" + TENANT_A + ":books:*")).hasSize(1);
    }

    @Test
    void evictMotMuc() {
        cache.get("books", "q=a", Duration.ofSeconds(30), BOOKS, this::load);
        cache.get("books", "q=b", Duration.ofSeconds(30), BOOKS, this::load);

        cache.evict("books", "q=a");

        assertThat(redis.keys("lms:v1:t:" + TENANT_A + ":books:*")).hasSize(1);
    }

    @Test
    void redisKhongKetNoiDuocThiVanTraDuLieu() {
        LettuceConnectionFactory dead = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 1));
        dead.afterPropertiesSet();
        StringRedisTemplate deadRedis = new StringRedisTemplate(dead);
        deadRedis.afterPropertiesSet();
        RedisRequestCache broken = new RedisRequestCache(deadRedis, new ObjectMapper().registerModule(new JavaTimeModule()),
                new CacheProperties(true, "lms", "1", Duration.ofSeconds(5), 10000), null);

        List<Book> result = broken.get("books", "q=a", Duration.ofSeconds(30), BOOKS, this::load);

        assertThat(result).hasSize(1);
        dead.destroy();
    }
}
