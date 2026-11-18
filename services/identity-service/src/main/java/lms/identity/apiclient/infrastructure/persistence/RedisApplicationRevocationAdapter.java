package lms.identity.apiclient.infrastructure.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lms.identity.apiclient.application.ApplicationRevocationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Ghi {@code lms:revoked:v1:app:{applicationId}} = (giây epoch của mốc + 1) với TTL (mặc định 1 giờ, lớn hơn TTL token
 * 5 phút). integration-api từ chối token có {@code iat} nhỏ hơn giá trị này. Best-effort: Redis lỗi chỉ log WARN.
 */
public class RedisApplicationRevocationAdapter implements ApplicationRevocationPort {

    private static final Logger log = LoggerFactory.getLogger(RedisApplicationRevocationAdapter.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisApplicationRevocationAdapter(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public void revokeTokensIssuedBefore(UUID applicationId, Instant at) {
        try {
            redis.opsForValue().set("lms:revoked:v1:app:" + applicationId, String.valueOf(at.getEpochSecond() + 1), ttl);
        } catch (RuntimeException e) {
            log.warn("Không ghi được dấu thu hồi token ({}): token đã cấp còn dùng được tối đa theo TTL của token",
                    e.getClass().getSimpleName());
        }
    }
}
