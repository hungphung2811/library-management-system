package lms.database.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lms.database.api.ConnectionSpec;
import lms.database.api.DatabaseType;
import lms.database.api.RedisConnection;
import lms.database.provider.redis.RedisProvider;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("docker")
@Testcontainers
class RedisProviderIT {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    private final RedisProvider provider = new RedisProvider();

    private ConnectionSpec spec() {
        ConnectionSpec spec = new ConnectionSpec();
        spec.setType(DatabaseType.REDIS);
        spec.setHost(redis.getHost());
        spec.setPort(redis.getMappedPort(6379));
        return spec;
    }

    @Test
    void healthUpVaGhiDoc() {
        try (RedisConnection connection = provider.create("CACHE_DB", spec())) {
            assertThat(connection.health().isUp()).isTrue();

            connection.stringTemplate().opsForValue().set("k", "giá trị", Duration.ofMinutes(1));

            assertThat(connection.stringTemplate().opsForValue().get("k")).isEqualTo("giá trị");
        }
    }

    @Test
    void chonDuocChiSoDatabase() {
        ConnectionSpec spec = spec();
        spec.setDatabase("2");
        try (RedisConnection db2 = provider.create("DB2", spec); RedisConnection db0 = provider.create("DB0", spec())) {
            db2.stringTemplate().opsForValue().set("only-in-2", "x");

            assertThat(db2.stringTemplate().hasKey("only-in-2")).isTrue();
            assertThat(db0.stringTemplate().hasKey("only-in-2")).isFalse();
        }
    }

    @Test
    void closeLaIdempotentVaHealthSauKhiDongLaDown() {
        RedisConnection connection = provider.create("CLOSE_DB", spec());

        connection.close();
        connection.close();

        assertThat(connection.health().isUp()).isFalse();
    }

    @Test
    void khongKetNoiDuocThiNemLoi() {
        ConnectionSpec dead = new ConnectionSpec();
        dead.setType(DatabaseType.REDIS);
        dead.setHost("localhost");
        dead.setPort(1);
        dead.getTimeout().setConnect(Duration.ofMillis(800));

        assertThatThrownBy(() -> provider.create("DEAD_DB", dead)).isInstanceOf(RuntimeException.class);
    }
}
