package lms.database.provider.redis;

import lms.database.api.DatabaseType;
import lms.database.api.HealthStatus;
import lms.database.api.RedisConnection;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

class DefaultRedisConnection implements RedisConnection {

    private final String name;
    private final LettuceConnectionFactory factory;
    private final StringRedisTemplate template;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    DefaultRedisConnection(String name, LettuceConnectionFactory factory, StringRedisTemplate template) {
        this.name = name;
        this.factory = factory;
        this.template = template;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public DatabaseType type() {
        return DatabaseType.REDIS;
    }

    @Override
    public RedisConnectionFactory connectionFactory() {
        return factory;
    }

    @Override
    public StringRedisTemplate stringTemplate() {
        return template;
    }

    @Override
    public HealthStatus health() {
        if (closed.get()) {
            return HealthStatus.down("Connection đã đóng");
        }
        try {
            String reply = template.execute((RedisCallback<String>) connection -> connection.ping());
            return "PONG".equalsIgnoreCase(reply) ? HealthStatus.up("REDIS") : HealthStatus.down("Phản hồi PING lạ: " + reply);
        } catch (RuntimeException e) {
            return HealthStatus.down(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            factory.destroy();
        }
    }
}
