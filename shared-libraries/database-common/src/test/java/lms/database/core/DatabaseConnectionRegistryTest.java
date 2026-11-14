package lms.database.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lms.database.api.ConnectionSpec;
import lms.database.api.ConnectionState;
import lms.database.api.DatabaseType;
import lms.database.api.RedisConnection;
import lms.database.api.exception.ConnectionDisabledException;
import lms.database.api.exception.ConnectionInitializationException;
import lms.database.api.exception.ConnectionNotFoundException;
import lms.database.api.exception.ConnectionTypeMismatchException;
import lms.database.api.exception.DuplicateConnectionException;
import lms.database.testsupport.FakeProvider;
import lms.database.testsupport.FakeProvider.FakeConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DatabaseConnectionRegistryTest {

    private final DatabaseConnectionRegistry registry = new DatabaseConnectionRegistry();
    private final List<String> closeLog = new CopyOnWriteArrayList<>();
    private final ConnectionSpec spec = new ConnectionSpec();

    private FakeConnection connection(String name) {
        return new FakeProvider(DatabaseType.REDIS, closeLog).create(name, spec);
    }

    @Test
    void dangKyRoiTraCuuKhongPhanBietHoaThuong() {
        FakeConnection connection = connection("USER_DB");
        registry.register("user_db", spec, connection);

        assertThat(registry.find("USER_DB")).containsSame(connection);
        assertThat(registry.require("User_Db", FakeConnection.class)).isSameAs(connection);
        assertThat(registry.state("user_db")).isEqualTo(ConnectionState.UP);
    }

    @Test
    void connectionKhongTonTaiBaoRoTenVaDanhSach() {
        registry.register("A", spec, connection("A"));

        assertThat(registry.find("B")).isEmpty();
        assertThatThrownBy(() -> registry.require("B", FakeConnection.class))
                .isInstanceOf(ConnectionNotFoundException.class)
                .hasMessageContaining("\"B\"").hasMessageContaining("[A]");
        assertThatThrownBy(() -> registry.state("B")).isInstanceOf(ConnectionNotFoundException.class);
    }

    @Test
    void connectionBiTatKhongDungDuoc() {
        registry.registerDisabled("OFF", spec);

        assertThat(registry.state("OFF")).isEqualTo(ConnectionState.DISABLED);
        assertThat(registry.find("OFF")).isEmpty();
        assertThatThrownBy(() -> registry.require("OFF", FakeConnection.class))
                .isInstanceOf(ConnectionDisabledException.class);
    }

    @Test
    void connectionLoiKhoiTaoDuocGhiNhan() {
        registry.registerFailed("BAD", spec, new IllegalStateException("không kết nối được"));

        assertThat(registry.state("BAD")).isEqualTo(ConnectionState.FAILED);
        assertThatThrownBy(() -> registry.require("BAD", FakeConnection.class))
                .isInstanceOf(ConnectionInitializationException.class).hasMessageContaining("không kết nối được");
    }

    @Test
    void dangKyTrungBiTuChoi() {
        registry.register("A", spec, connection("A"));

        assertThatThrownBy(() -> registry.register("a", spec, connection("a")))
                .isInstanceOf(DuplicateConnectionException.class);
        assertThat(registry.names()).containsExactly("A");
    }

    @Test
    void sai_kieuConnectionBiBao() {
        registry.register("A", spec, connection("A"));

        assertThatThrownBy(() -> registry.require("A", RedisConnection.class))
                .isInstanceOf(ConnectionTypeMismatchException.class);
    }

    @Test
    void entriesGiuThuTuDangKyVaRemoveGoDuoc() {
        registry.register("C", spec, connection("C"));
        registry.registerDisabled("A", spec);
        registry.register("B", spec, connection("B"));

        assertThat(registry.entries()).extracting(RegisteredConnection::name).containsExactly("C", "A", "B");
        assertThat(registry.remove("a")).isPresent();
        assertThat(registry.names()).containsExactly("C", "B");
        assertThat(registry.remove("a")).isEmpty();
    }

    @Test
    void dangKyDongThoiCungTenChiMotLuongThanhCong() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit((Callable<Void>) () -> {
                start.await();
                try {
                    registry.register("SAME", spec, connection("SAME"));
                    success.incrementAndGet();
                } catch (DuplicateConnectionException e) {
                    duplicate.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();

        assertThat(success.get()).isEqualTo(1);
        assertThat(duplicate.get()).isEqualTo(threads - 1);
        assertThat(registry.names()).containsExactly("SAME");
    }

    @Test
    void dangKyVaTraCuuSongSongNhieuTenKhacNhau() throws Exception {
        int count = 200;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String name = "DB_" + i;
            futures.add(pool.submit(() -> {
                registry.register(name, spec, connection(name));
                assertThat(registry.require(name, FakeConnection.class).name()).isEqualTo(name);
            }));
        }
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();

        assertThat(registry.names()).hasSize(count);
        assertThat(registry.entries()).hasSize(count);
    }
}
