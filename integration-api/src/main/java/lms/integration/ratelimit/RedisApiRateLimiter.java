package lms.integration.ratelimit;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Cửa sổ cố định 60 giây trong Redis (D6): một script Lua nguyên tử làm {@code INCR} + {@code EXPIRE} cho khóa
 * {@code lms:rl:v1:t:{tenantId}:a:{applicationId}:{group}:{epochMinute}} (TTL 120 giây) và, cùng lượt, bộ đếm sử dụng
 * {@code lms:usage:v1:t:{tenantId}:a:{applicationId}:{yyyyMMdd}} (UTC, TTL 35 ngày; chỉ đếm, chưa có API đọc; đếm cả
 * request bị từ chối).
 *
 * <p>Fail-open: Redis lỗi hoặc chậm thì KHÔNG chặn request; chỉ log WARN rồi tạm bỏ qua Redis
 * {@value #SKIP_REDIS_MILLIS_AFTER_ERROR} ms (nên log tối đa một dòng mỗi khoảng đó mỗi instance). Script dùng hai khóa nên
 * chỉ đúng với Redis đơn hoặc Sentinel; Redis Cluster cần hash tag (chưa làm).
 */
public class RedisApiRateLimiter implements ApiRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisApiRateLimiter.class);

    static final long SKIP_REDIS_MILLIS_AFTER_ERROR = 5_000;
    static final long WINDOW_MILLIS = 60_000;
    static final String WINDOW_TTL_SECONDS = "120";
    static final String USAGE_TTL_SECONDS = String.valueOf(35L * 24 * 3600);

    private static final RedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            local u = redis.call('INCR', KEYS[2])
            if u == 1 then redis.call('EXPIRE', KEYS[2], ARGV[2]) end
            return c
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final int limitPerMinute;
    private final AtomicLong skipRedisUntilMillis = new AtomicLong();

    public RedisApiRateLimiter(StringRedisTemplate redis, Clock clock, int limitPerMinute) {
        this.redis = redis;
        this.clock = clock;
        this.limitPerMinute = limitPerMinute;
    }

    @Override
    public Optional<Decision> check(ApiCaller caller, String group) {
        long now = clock.millis();
        if (now < skipRedisUntilMillis.get()) return Optional.empty();
        long epochMinute = now / WINDOW_MILLIS;
        String prefix = "lms:%s:v1:t:" + caller.tenantId() + ":a:" + caller.applicationId() + ":";
        String windowKey = prefix.formatted("rl") + group + ":" + epochMinute;
        String usageKey = prefix.formatted("usage")
                + LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE);
        Long count;
        try {
            count = redis.execute(SCRIPT, List.of(windowKey, usageKey), WINDOW_TTL_SECONDS, USAGE_TTL_SECONDS);
        } catch (RuntimeException e) {
            failOpen(now, e);
            return Optional.empty();
        }
        if (count == null) {
            failOpen(now, null);
            return Optional.empty();
        }
        long retryAfter = Math.max(1, (((epochMinute + 1) * WINDOW_MILLIS - now) + 999) / 1000);
        return Optional.of(new Decision(count <= limitPerMinute, limitPerMinute,
                Math.max(0, limitPerMinute - count), retryAfter));
    }

    private void failOpen(long now, RuntimeException e) {
        skipRedisUntilMillis.set(now + SKIP_REDIS_MILLIS_AFTER_ERROR);
        // Một dòng mỗi lần mở cửa sổ bỏ qua; không log khóa (chứa tenant/application), chỉ loại lỗi.
        log.warn("Redis giới hạn tốc độ lỗi ({}), bỏ qua giới hạn trong {} ms (fail-open)",
                e == null ? "phản hồi rỗng" : e.getClass().getSimpleName(), SKIP_REDIS_MILLIS_AFTER_ERROR);
    }
}
