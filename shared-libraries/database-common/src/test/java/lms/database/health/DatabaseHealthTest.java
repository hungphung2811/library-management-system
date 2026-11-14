package lms.database.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lms.database.api.ConnectionSpec;
import lms.database.api.DatabaseConnection;
import lms.database.api.DatabaseType;
import lms.database.api.HealthStatus;
import lms.database.api.exception.ConnectionNotFoundException;
import lms.database.core.DatabaseConnectionRegistry;
import lms.database.testsupport.FakeProvider;
import lms.database.testsupport.FakeProvider.FakeConnection;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

class DatabaseHealthTest {

    private final DatabaseConnectionRegistry registry = new DatabaseConnectionRegistry();
    private final DatabaseHealthChecker checker = new DatabaseHealthChecker(registry);
    private final DatabaseHealthIndicator indicator = new DatabaseHealthIndicator(registry, checker);

    private FakeConnection fake(String name) {
        return new FakeProvider(DatabaseType.REDIS, new ArrayList<>()).create(name, new ConnectionSpec());
    }

    private ConnectionSpec spec(boolean critical) {
        ConnectionSpec spec = new ConnectionSpec();
        spec.setCritical(critical);
        return spec;
    }

    @Test
    void checkerPhanBietUpDownDisabledFailed() {
        registry.register("UP", spec(true), fake("UP"));
        registry.register("DOWN", spec(true), fake("DOWN").withHealth(HealthStatus.down("mất kết nối")));
        registry.registerDisabled("OFF", spec(true));
        registry.registerFailed("BAD", spec(true), new IllegalStateException("không mở được"));

        assertThat(checker.check("UP").isUp()).isTrue();
        assertThat(checker.check("DOWN").status()).isEqualTo(HealthStatus.Status.DOWN);
        assertThat(checker.check("OFF").status()).isEqualTo(HealthStatus.Status.DISABLED);
        assertThat(checker.check("BAD").status()).isEqualTo(HealthStatus.Status.DOWN);
        assertThat(checker.checkAll()).containsOnlyKeys("UP", "DOWN", "OFF", "BAD");
        assertThatThrownBy(() -> checker.check("NOPE")).isInstanceOf(ConnectionNotFoundException.class);
    }

    @Test
    void connectionNemExceptionKhiHealthThiThanhDown() {
        DatabaseConnection exploding = new FakeConnection("X", DatabaseType.REDIS, new ArrayList<>()) {
            @Override
            public HealthStatus health() {
                throw new IllegalStateException("boom");
            }
        };
        registry.register("X", spec(true), exploding);

        assertThat(checker.check("X").status()).isEqualTo(HealthStatus.Status.DOWN);
    }

    @Test
    void connectionCriticalDownLamTongDown() {
        registry.register("MAIN", spec(true), fake("MAIN").withHealth(HealthStatus.down("lỗi")));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("MAIN");
    }

    @Test
    void connectionPhuDownHoacBiTatKhongLamTongDown() {
        registry.register("MAIN", spec(true), fake("MAIN"));
        registry.register("CACHE", spec(false), fake("CACHE").withHealth(HealthStatus.down("cache lỗi")));
        registry.registerDisabled("OFF", spec(true));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails().get("CACHE").toString()).contains("DOWN");
        assertThat(health.getDetails().get("OFF").toString()).contains("DISABLED");
    }
}
