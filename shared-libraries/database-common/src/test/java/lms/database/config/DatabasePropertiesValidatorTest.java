package lms.database.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lms.database.api.ConnectionSpec;
import lms.database.api.DatabaseType;
import lms.database.api.exception.InvalidDatabaseConfigurationException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DatabasePropertiesValidatorTest {

    private final DatabasePropertiesValidator validator = new DatabasePropertiesValidator();
    private final Set<DatabaseType> all = EnumSet.allOf(DatabaseType.class);

    private ConnectionSpec postgres() {
        ConnectionSpec spec = new ConnectionSpec();
        spec.setType(DatabaseType.POSTGRESQL);
        spec.setUrl("jdbc:postgresql://localhost:5432/app");
        spec.setUsername("app");
        spec.setPassword("secret");
        return spec;
    }

    private InvalidDatabaseConfigurationException errorsOf(Map<String, ConnectionSpec> specs, Set<DatabaseType> supported) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> validator.validate(specs, supported));
        assertThat(thrown).isInstanceOf(InvalidDatabaseConfigurationException.class);
        return (InvalidDatabaseConfigurationException) thrown;
    }

    @Test
    void cauHinhHopLeKhongNemLoi() {
        ConnectionSpec oracle = new ConnectionSpec();
        oracle.setType(DatabaseType.ORACLE);
        oracle.setUrl("jdbc:oracle:thin:@//localhost:1521/FREEPDB1");
        oracle.setUsername("app");
        ConnectionSpec mongo = new ConnectionSpec();
        mongo.setType(DatabaseType.MONGODB);
        mongo.setUri("mongodb://localhost:27017/log");
        ConnectionSpec redis = new ConnectionSpec();
        redis.setType(DatabaseType.REDIS);
        redis.setHost("localhost");
        redis.setPort(6379);

        assertThatCode(() -> validator.validate(
                Map.of("USER_DB", postgres(), "ORDER_DB", oracle, "LOG_DB", mongo, "CACHE_DB", redis), all))
                .doesNotThrowAnyException();
    }

    @Test
    void gomTatCaLoiMotLuot() {
        ConnectionSpec bad = new ConnectionSpec();
        bad.setType(DatabaseType.POSTGRESQL);
        bad.setUrl("mysql://sai-dinh-dang");
        ConnectionSpec noType = new ConnectionSpec();
        ConnectionSpec mongo = new ConnectionSpec();
        mongo.setType(DatabaseType.MONGODB);
        Map<String, ConnectionSpec> specs = new LinkedHashMap<>();
        specs.put("A", bad);
        specs.put("B", noType);
        specs.put("C", mongo);

        InvalidDatabaseConfigurationException ex = errorsOf(specs, all);

        assertThat(ex.errors()).anyMatch(e -> e.contains("[A]") && e.contains("jdbc:postgresql:"))
                .anyMatch(e -> e.contains("[A]") && e.contains("username"))
                .anyMatch(e -> e.contains("[B]") && e.contains("type"))
                .anyMatch(e -> e.contains("[C]") && e.contains("uri"));
    }

    @Test
    void typeChuaCoProviderBiTuChoi() {
        InvalidDatabaseConfigurationException ex = errorsOf(Map.of("USER_DB", postgres()), EnumSet.of(DatabaseType.REDIS));

        assertThat(ex.errors()).singleElement().asString().contains("chưa có provider");
    }

    @Test
    void tenTrungKhongPhanBietHoaThuong() {
        Map<String, ConnectionSpec> specs = new LinkedHashMap<>();
        specs.put("USER_DB", postgres());
        specs.put("user_db", postgres());

        assertThat(errorsOf(specs, all).errors()).anyMatch(e -> e.contains("trùng tên"));
    }

    @Test
    void connectionTatChiCanTypeHopLe() {
        ConnectionSpec disabled = new ConnectionSpec();
        disabled.setType(DatabaseType.POSTGRESQL);
        disabled.setEnabled(false);

        assertThatCode(() -> validator.validate(Map.of("OFF", disabled), all)).doesNotThrowAnyException();
    }

    @Test
    void placeholderChuaResolveBiBaoLoi() {
        ConnectionSpec spec = postgres();
        spec.setPassword("${DB_PASSWORD}");

        assertThat(errorsOf(Map.of("USER_DB", spec), all).errors()).anyMatch(e -> e.contains("placeholder"));
    }

    @Test
    void poolTimeoutRetryPhaiHopLy() {
        ConnectionSpec spec = postgres();
        spec.getPool().setMaxSize(2);
        spec.getPool().setMinIdle(5);
        spec.getTimeout().setConnect(Duration.ZERO);
        spec.getRetry().setMaxAttempts(0);
        spec.getRetry().setMultiplier(0.5);

        assertThat(errorsOf(Map.of("USER_DB", spec), all).errors())
                .anyMatch(e -> e.contains("min-idle"))
                .anyMatch(e -> e.contains("timeout.connect"))
                .anyMatch(e -> e.contains("max-attempts"))
                .anyMatch(e -> e.contains("multiplier"));
    }

    @Test
    void redisPortVaDatabaseHopLe() {
        ConnectionSpec redis = new ConnectionSpec();
        redis.setType(DatabaseType.REDIS);
        redis.setHost("localhost");
        redis.setPort(70000);
        redis.setDatabase("abc");

        assertThat(errorsOf(Map.of("CACHE", redis), all).errors())
                .anyMatch(e -> e.contains("port")).anyMatch(e -> e.contains("database"));
    }

    @Test
    void toStringCheSecret() {
        ConnectionSpec spec = postgres();
        spec.setUrl("jdbc:postgresql://h/db?user=u&password=hunter2");
        ConnectionSpec mongo = new ConnectionSpec();
        mongo.setUri("mongodb://admin:hunter2@host:27017/db");

        assertThat(spec.toString()).doesNotContain("secret").doesNotContain("hunter2");
        assertThat(mongo.toString()).doesNotContain("hunter2").doesNotContain("admin");
        assertThatThrownBy(() -> validator.validate(Map.of("X", new ConnectionSpec()), all))
                .hasMessageNotContaining("hunter2");
    }
}
