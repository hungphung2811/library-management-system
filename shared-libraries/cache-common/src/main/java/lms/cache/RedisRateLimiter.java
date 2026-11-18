package lms.cache;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import lms.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link RateLimiter} bằng Redis: khóa {@code {prefix}:v{version}:rl:{t:tenant|g}:{bucket}:{sha256(subject)}:{cửa sổ}},
 * INCR rồi đặt hạn sống bằng cửa sổ ở lần đầu (hai lệnh, nếu EXPIRE lỗi thì khóa còn lại tới khi Redis dọn nên đặt thêm
 * hạn ở mọi lượt đếm đầu cửa sổ; khóa mồ côi hiếm và vô hại vì chỉ sai lệch trong một cửa sổ). Fail-open có lùi như cache.
 */
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    private static final Pattern BUCKET = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final StringRedisTemplate redis;
    private final CacheProperties properties;
    private final MeterRegistry meters;
    private final LongSupplier millisClock;
    private final LongSupplier nanoClock;
    private volatile long skipRedisUntilNanos;
    private volatile boolean skipping;

    public RedisRateLimiter(StringRedisTemplate redis, CacheProperties properties, MeterRegistry meters) {
        this(redis, properties, meters, System::currentTimeMillis, System::nanoTime);
    }

    RedisRateLimiter(StringRedisTemplate redis, CacheProperties properties, MeterRegistry meters,
                     LongSupplier millisClock, LongSupplier nanoClock) {
        this.redis = redis;
        this.properties = properties;
        this.meters = meters;
        this.millisClock = millisClock;
        this.nanoClock = nanoClock;
    }

    @Override
    public boolean tryAcquire(String bucket, String subject, int limit, Duration window) {
        if (bucket == null || !BUCKET.matcher(bucket).matches()) {
            throw new IllegalArgumentException("bucket chỉ gồm chữ, số, '.', '_', '-' (tối đa 64 ký tự): " + bucket);
        }
        if (limit < 1 || window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("limit phải >= 1 và window phải dương");
        }
        if (redisSkipped()) {
            count("bypass", bucket);
            return true;
        }
        long windowMillis = Math.max(1, window.toMillis());
        long slot = millisClock.getAsLong() / windowMillis;
        String key = properties.keyPrefix() + ":v" + properties.version() + ":rl:" + scope() + ":" + bucket + ":"
                + sha256(String.valueOf(subject)) + ":" + slot;
        try {
            Long n = redis.opsForValue().increment(key);
            if (n == null) {
                return true;
            }
            if (n == 1L) {
                redis.expire(key, Duration.ofMillis(windowMillis + 1_000));
            }
            boolean allowed = n <= limit;
            count(allowed ? "allowed" : "limited", bucket);
            return allowed;
        } catch (Exception e) {
            long backoff = properties.errorBackoff().toNanos();
            if (backoff > 0) {
                skipRedisUntilNanos = nanoClock.getAsLong() + backoff;
                skipping = true;
            }
            count("error", bucket);
            log.warn("Giới hạn tốc độ lỗi Redis ({}), cho qua: {}", bucket, e.toString());
            return true;
        }
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    private static String scope() {
        if (TenantContext.isAllTenants()) {
            return "g";
        }
        return TenantContext.current().map(id -> "t:" + id).orElse("g");
    }

    private boolean redisSkipped() {
        if (!skipping) return false;
        if (nanoClock.getAsLong() - skipRedisUntilNanos < 0) return true;
        skipping = false;
        return false;
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void count(String result, String bucket) {
        if (meters != null) {
            meters.counter("lms.ratelimit.requests", "result", result, "bucket", bucket).increment();
        }
    }
}
