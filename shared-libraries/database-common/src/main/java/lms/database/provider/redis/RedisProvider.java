package lms.database.provider.redis;

import lms.database.api.ConnectionSpec;
import lms.database.api.DatabaseProvider;
import lms.database.api.DatabaseType;
import lms.database.api.RedisConnection;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Redis qua Spring Data Redis (Lettuce). Lettuce dùng một kết nối đa luồng nên không có pool kiểu Hikari. */
public class RedisProvider implements DatabaseProvider<RedisConnection> {

    @Override
    public DatabaseType type() {
        return DatabaseType.REDIS;
    }

    @Override
    public RedisConnection create(String name, ConnectionSpec spec) {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(spec.getHost(),
                spec.getPort() == null ? 6379 : spec.getPort());
        if (spec.getUsername() != null && !spec.getUsername().isBlank()) {
            configuration.setUsername(spec.getUsername());
        }
        if (spec.getPassword() != null && !spec.getPassword().isBlank()) {
            configuration.setPassword(RedisPassword.of(spec.getPassword()));
        }
        if (spec.getDatabase() != null && !spec.getDatabase().isBlank()) {
            configuration.setDatabase(Integer.parseInt(spec.getDatabase().trim()));
        }

        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(spec.getTimeout().getValidation())
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder().connectTimeout(spec.getTimeout().getConnect()).build())
                        .build())
                .build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration, client);
        factory.afterPropertiesSet();
        factory.start();
        try {
            StringRedisTemplate template = new StringRedisTemplate(factory);
            // Kiểm tra kết nối thật ngay lúc tạo để manager còn retry được.
            template.execute((RedisCallback<String>) connection -> connection.ping());
            return new DefaultRedisConnection(name, factory, template);
        } catch (RuntimeException e) {
            factory.destroy();
            throw e;
        }
    }
}
