package lms.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lms.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Cài đặt {@link RequestCache} trên Redis. Khóa: {@code {prefix}:v{version}:t:{tenantId}:{namespace}:{sha256(key)}}
 * (hoặc {@code g} thay cho {@code t:{tenantId}} với dữ liệu toàn nền tảng). Giá trị là JSON.
 *
 * <p>Fail-open: mọi lỗi Redis hoặc dữ liệu cache hỏng chỉ log cảnh báo và rơi về loader. Sau lỗi Redis, tạm bỏ qua Redis
 * {@link CacheProperties#errorBackoff()} để không phải chờ timeout ở mỗi request.
 */
public class RedisRequestCache implements RequestCache {

    private static final Logger log = LoggerFactory.getLogger(RedisRequestCache.class);
    private static final Pattern NAMESPACE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final CacheProperties properties;
    private final MeterRegistry meters;
    private final LongSupplier nanoClock;
    private volatile long skipRedisUntilNanos;
    private volatile boolean skipping;

    public RedisRequestCache(StringRedisTemplate redis, ObjectMapper mapper, CacheProperties properties,
                             MeterRegistry meters) {
        this(redis, mapper, properties, meters, System::nanoTime);
    }

    RedisRequestCache(StringRedisTemplate redis, ObjectMapper mapper, CacheProperties properties,
                      MeterRegistry meters, LongSupplier nanoClock) {
        this.redis = redis;
        this.mapper = mapper;
        this.properties = properties;
        this.meters = meters;
        this.nanoClock = nanoClock;
    }

    @Override
    public <T> T get(String namespace, Object key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
        String scope = tenantScope();
        if (scope == null) {
            count("bypass", namespace);
            return loader.get();
        }
        return fetch(scope, namespace, key, ttl, type, loader);
    }

    @Override
    public <T> T getGlobal(String namespace, Object key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
        return fetch("g", namespace, key, ttl, type, loader);
    }

    private <T> T fetch(String scope, String namespace, Object key, Duration ttl, TypeReference<T> type,
                        Supplier<T> loader) {
        checkNamespace(namespace);
        if (ttl == null || ttl.isZero() || ttl.isNegative() || redisSkipped()) {
            count("bypass", namespace);
            return loader.get();
        }
        String redisKey = redisKey(scope, namespace, key);
        try {
            String json = redis.opsForValue().get(redisKey);
            if (json != null) {
                T cached = mapper.readValue(json, mapper.getTypeFactory().constructType(type.getType()));
                count("hit", namespace);
                return cached;
            }
        } catch (Exception e) {
            // Redis lỗi hoặc dữ liệu cache hỏng (đổi cấu trúc sau khi triển khai): coi như trượt, ghi đè sau khi có kết quả
            onError(namespace, "đọc", redisKey, e);
        }
        count("miss", namespace);
        T value = loader.get(); // lỗi của loader ném nguyên, không ghi cache
        if (value != null && !redisSkipped()) {
            try {
                String json = mapper.writeValueAsString(value);
                if (json.length() <= properties.maxValueChars()) {
                    redis.opsForValue().set(redisKey, json, ttl);
                } else {
                    log.debug("Không cache {} vì giá trị quá lớn ({} ký tự)", namespace, json.length());
                }
            } catch (Exception e) {
                onError(namespace, "ghi", redisKey, e);
            }
        }
        return value;
    }

    @Override
    public void evict(String namespace, Object key) {
        checkNamespace(namespace);
        String scope = tenantScope();
        if (scope == null) return;
        String redisKey = redisKey(scope, namespace, key);
        afterCommit(() -> {
            try {
                redis.delete(redisKey);
            } catch (Exception e) {
                onError(namespace, "xóa", redisKey, e);
            }
        });
    }

    @Override
    public void evictNamespace(String namespace) {
        checkNamespace(namespace);
        String scope = tenantScope();
        if (scope == null) return;
        String pattern = prefix(scope) + namespace + ":*";
        afterCommit(() -> deleteByPattern(namespace, pattern));
    }

    @Override
    public void evictGlobal(String namespace, Object key) {
        checkNamespace(namespace);
        String redisKey = redisKey("g", namespace, key);
        afterCommit(() -> {
            try {
                redis.delete(redisKey);
            } catch (Exception e) {
                onError(namespace, "xóa", redisKey, e);
            }
        });
    }

    @Override
    public void evictGlobalNamespace(String namespace) {
        checkNamespace(namespace);
        String pattern = prefix("g") + namespace + ":*";
        afterCommit(() -> deleteByPattern(namespace, pattern));
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    private void deleteByPattern(String namespace, String pattern) {
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(pattern).count(200).build())) {
            while (cursor.hasNext()) {
                redis.delete(cursor.next());
            }
        } catch (Exception e) {
            onError(namespace, "xóa namespace", pattern, e);
        }
    }

    /** "t:{tenantId}" cho tenant hiện tại; null (không cache) khi không có tenant hoặc đang đọc mọi tenant. */
    private static String tenantScope() {
        if (TenantContext.isAllTenants()) return null;
        return TenantContext.current().map((UUID id) -> "t:" + id).orElse(null);
    }

    private String prefix(String scope) {
        return properties.keyPrefix() + ":v" + properties.version() + ":" + scope + ":";
    }

    private String redisKey(String scope, String namespace, Object key) {
        return prefix(scope) + namespace + ":" + sha256(String.valueOf(key));
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void checkNamespace(String namespace) {
        // Chặn ký tự đặc biệt của mẫu SCAN (*, ?, [) và dấu ':' để namespace không đụng phần khác của khóa
        if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("namespace chỉ gồm chữ, số, '.', '_', '-' (tối đa 64 ký tự): " + namespace);
        }
    }

    private boolean redisSkipped() {
        if (!skipping) return false;
        if (nanoClock.getAsLong() - skipRedisUntilNanos < 0) return true;
        skipping = false;
        return false;
    }

    private void onError(String namespace, String action, String target, Exception e) {
        long backoff = properties.errorBackoff().toNanos();
        if (backoff > 0) {
            skipRedisUntilNanos = nanoClock.getAsLong() + backoff;
            skipping = true;
        }
        count("error", namespace);
        log.warn("Cache {} lỗi ({}), rơi về nguồn dữ liệu: {}", action, target, e.toString());
    }

    private void count(String result, String namespace) {
        if (meters != null) {
            meters.counter("lms.cache.requests", "result", result, "namespace", namespace).increment();
        }
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
