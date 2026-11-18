package lms.integration.revocation;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Hai khóa, đọc bằng MỘT lượt MGET:
 * <ul>
 *   <li>{@code lms:revoked:v1:jti:{jti}} (denylist theo token, TTL = thời gian còn lại của token): tồn tại là thu hồi;</li>
 *   <li>{@code lms:revoked:v1:app:{applicationId}} (identity ghi khi tạm ngưng/xóa Application hoặc thu hồi credential
 *   COMPROMISED): giá trị là giây epoch, token có {@code iat} nhỏ hơn giá trị này bị thu hồi.</li>
 * </ul>
 * Khóa chỉ chứa UUID đã parse và jti đúng mẫu {@code [A-Za-z0-9-]{1,64}} (jti lạ thì bỏ qua phần jti). Fail-open: Redis lỗi
 * hoặc phản hồi rỗng thì KHÔNG chặn, log WARN và tạm bỏ qua Redis {@value #SKIP_REDIS_MILLIS_AFTER_ERROR} ms. Giá trị dấu không
 * phải số cũng bị bỏ qua (không thu hồi).
 */
public class RedisTokenRevocationChecker implements TokenRevocationChecker {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenRevocationChecker.class);

    static final long SKIP_REDIS_MILLIS_AFTER_ERROR = 5_000;
    private static final Pattern SAFE_JTI = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final AtomicLong skipRedisUntilMillis = new AtomicLong();

    public RedisTokenRevocationChecker(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public boolean isRevoked(Jwt jwt) {
        long now = clock.millis();
        if (now < skipRedisUntilMillis.get()) return false;
        String jti = jwt.getId() != null && SAFE_JTI.matcher(jwt.getId()).matches() ? jwt.getId() : null;
        UUID application = uuid(jwt.getClaimAsString("application_id"));
        if (jti == null && application == null) return false;
        String jtiKey = jti == null ? "lms:revoked:v1:jti:" + "-" : "lms:revoked:v1:jti:" + jti;
        String appKey = application == null ? "lms:revoked:v1:app:-" : "lms:revoked:v1:app:" + application;
        List<String> values;
        try {
            values = redis.opsForValue().multiGet(List.of(jtiKey, appKey));
        } catch (RuntimeException e) {
            failOpen(now, e);
            return false;
        }
        if (values == null || values.size() != 2) {
            failOpen(now, null);
            return false;
        }
        if (jti != null && values.get(0) != null) return true;
        String marker = application == null ? null : values.get(1);
        if (marker == null || jwt.getIssuedAt() == null) return false;
        try {
            return jwt.getIssuedAt().getEpochSecond() < Long.parseLong(marker.trim());
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void failOpen(long now, RuntimeException e) {
        skipRedisUntilMillis.set(now + SKIP_REDIS_MILLIS_AFTER_ERROR);
        log.warn("Redis thu hồi token lỗi ({}), bỏ qua kiểm thu hồi trong {} ms (fail-open)",
                e == null ? "phản hồi rỗng" : e.getClass().getSimpleName(), SKIP_REDIS_MILLIS_AFTER_ERROR);
    }

    private static UUID uuid(String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
