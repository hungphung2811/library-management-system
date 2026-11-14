package lms.database.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lms.database.api.ConnectionSpec;
import lms.database.api.ConnectionState;
import lms.database.api.DatabaseProvider;
import lms.database.api.DatabaseType;
import lms.database.api.exception.ConnectionInitializationException;
import lms.database.api.exception.ConnectionNotFoundException;
import lms.database.api.exception.DuplicateProviderException;
import lms.database.api.exception.UnsupportedDatabaseTypeException;
import lms.database.testsupport.FakeProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class DatabaseConnectionManagerTest {

    private final DatabaseConnectionRegistry registry = new DatabaseConnectionRegistry();
    private final List<Duration> sleeps = new ArrayList<>();
    private final RetryExecutor retry = new RetryExecutor(sleeps::add);
    private final List<String> closeLog = new CopyOnWriteArrayList<>();

    private ConnectionSpec spec(DatabaseType type) {
        ConnectionSpec spec = new ConnectionSpec();
        spec.setType(type);
        spec.getRetry().setMaxAttempts(3);
        spec.getRetry().setBackoff(Duration.ofMillis(100));
        spec.getRetry().setMultiplier(2.0);
        return spec;
    }

    private DatabaseConnectionManager manager(DatabaseProvider<?>... providers) {
        return new DatabaseConnectionManager(List.of(providers), registry, retry);
    }

    @Test
    void chonProviderTheoTypeKhongCanSwitch() {
        FakeProvider redis = new FakeProvider(DatabaseType.REDIS, closeLog);
        FakeProvider mongo = new FakeProvider(DatabaseType.MONGODB, closeLog);
        DatabaseConnectionManager manager = manager(redis, mongo);

        manager.open("CACHE", spec(DatabaseType.REDIS), true);
        manager.open("LOG", spec(DatabaseType.MONGODB), true);

        assertThat(manager.getConnection("cache").type()).isEqualTo(DatabaseType.REDIS);
        assertThat(manager.getConnection("LOG").type()).isEqualTo(DatabaseType.MONGODB);
        assertThat(redis.createCalls()).isEqualTo(1);
        assertThat(manager.supportedTypes()).containsExactlyInAnyOrder(DatabaseType.REDIS, DatabaseType.MONGODB);
    }

    @Test
    void providerTrungTypeBiTuChoiNgayKhiKhoiTao() {
        assertThatThrownBy(() -> manager(new FakeProvider(DatabaseType.REDIS, closeLog),
                new FakeProvider(DatabaseType.REDIS, closeLog))).isInstanceOf(DuplicateProviderException.class);
    }

    @Test
    void connectionBiTatKhongGoiProvider() {
        FakeProvider redis = new FakeProvider(DatabaseType.REDIS, closeLog);
        DatabaseConnectionManager manager = manager(redis);
        ConnectionSpec off = spec(DatabaseType.REDIS);
        off.setEnabled(false);

        manager.open("OFF", off, true);

        assertThat(redis.createCalls()).isZero();
        assertThat(manager.getConnectionStatus("OFF")).isEqualTo(ConnectionState.DISABLED);
    }

    @Test
    void typeChuaCoProviderBaoLoiRo() {
        DatabaseConnectionManager manager = manager(new FakeProvider(DatabaseType.REDIS, closeLog));

        assertThatThrownBy(() -> manager.open("X", spec(DatabaseType.ORACLE), true))
                .isInstanceOf(UnsupportedDatabaseTypeException.class).hasMessageContaining("ORACLE");
    }

    @Test
    void connectionKhongTonTaiBaoLoi() {
        DatabaseConnectionManager manager = manager();

        assertThatThrownBy(() -> manager.getConnection("NOPE")).isInstanceOf(ConnectionNotFoundException.class);
        assertThatThrownBy(() -> manager.getConnectionStatus("NOPE")).isInstanceOf(ConnectionNotFoundException.class);
    }

    @Test
    void retryRoiThanhCongVoiBackoffLuyThua() {
        FakeProvider flaky = new FakeProvider(DatabaseType.REDIS, closeLog)
                .failing(2, () -> new IllegalStateException("chưa sẵn sàng"));
        DatabaseConnectionManager manager = manager(flaky);

        manager.open("CACHE", spec(DatabaseType.REDIS), true);

        assertThat(flaky.createCalls()).isEqualTo(3);
        assertThat(sleeps).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200));
        assertThat(manager.getConnectionStatus("CACHE")).isEqualTo(ConnectionState.UP);
    }

    @Test
    void hetLanThuVaFailFastThiNemLoi() {
        FakeProvider broken = new FakeProvider(DatabaseType.REDIS, closeLog)
                .failing(99, () -> new IllegalStateException("down"));
        DatabaseConnectionManager manager = manager(broken);

        assertThatThrownBy(() -> manager.open("CACHE", spec(DatabaseType.REDIS), true))
                .isInstanceOf(ConnectionInitializationException.class).hasMessageContaining("CACHE");
        assertThat(broken.createCalls()).isEqualTo(3);
        assertThat(registry.entry("CACHE")).isEmpty();
    }

    @Test
    void hetLanThuNhungChoPhepTiepTucThiDanhDauFailed() {
        FakeProvider broken = new FakeProvider(DatabaseType.REDIS, closeLog)
                .failing(99, () -> new IllegalStateException("down"));
        DatabaseConnectionManager manager = manager(broken);

        manager.open("CACHE", spec(DatabaseType.REDIS), false);

        assertThat(manager.getConnectionStatus("CACHE")).isEqualTo(ConnectionState.FAILED);
    }

    @Test
    void connectionPhuLoiKhongChanKhoiDongDuFailFast() {
        FakeProvider broken = new FakeProvider(DatabaseType.REDIS, closeLog)
                .failing(99, () -> new IllegalStateException("down"));
        DatabaseConnectionManager manager = manager(broken);
        ConnectionSpec optional = spec(DatabaseType.REDIS);
        optional.setCritical(false);

        manager.open("CACHE", optional, true);

        assertThat(manager.getConnectionStatus("CACHE")).isEqualTo(ConnectionState.FAILED);
    }

    @Test
    void dongNguocThuTuMoVaLoiMotConnectionKhongChanCacConnectionKhac() {
        DatabaseConnectionManager manager = manager(new FakeProvider(DatabaseType.REDIS, closeLog),
                new FakeProvider(DatabaseType.MONGODB, closeLog));
        Map<String, ConnectionSpec> specs = new LinkedHashMap<>();
        specs.put("FIRST", spec(DatabaseType.REDIS));
        specs.put("SECOND", spec(DatabaseType.MONGODB));
        ConnectionSpec off = spec(DatabaseType.REDIS);
        off.setEnabled(false);
        specs.put("OFF", off);
        manager.initializeConnections(specs, true);
        ((FakeProvider.FakeConnection) manager.getConnection("SECOND")).failOnClose();

        manager.closeAllConnections();

        assertThat(closeLog).containsExactly("SECOND", "FIRST");
        assertThat(registry.names()).isEmpty();
    }

    @Test
    void dongMotConnectionRoiGoKhoiRegistry() {
        DatabaseConnectionManager manager = manager(new FakeProvider(DatabaseType.REDIS, closeLog));
        manager.open("CACHE", spec(DatabaseType.REDIS), true);

        manager.closeConnection("cache");
        manager.closeConnection("cache"); // gọi lại không lỗi

        assertThat(closeLog).containsExactly("CACHE");
        assertThatThrownBy(() -> manager.getConnection("CACHE")).isInstanceOf(ConnectionNotFoundException.class);
    }

    @Test
    void healthCheckUyQuyenChoConnection() {
        DatabaseConnectionManager manager = manager(new FakeProvider(DatabaseType.REDIS, closeLog));
        manager.open("CACHE", spec(DatabaseType.REDIS), true);

        assertThat(manager.healthCheck("CACHE").isUp()).isTrue();
    }
}
