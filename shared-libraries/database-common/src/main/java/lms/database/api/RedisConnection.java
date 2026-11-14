package lms.database.api;

import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Kết nối Redis dùng Spring Data Redis (Lettuce). */
public interface RedisConnection extends DatabaseConnection {

    RedisConnectionFactory connectionFactory();

    StringRedisTemplate stringTemplate();
}
