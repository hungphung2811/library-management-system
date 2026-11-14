package lms.database.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lms.database.api.RetryProperties;
import lms.database.api.exception.RetryExhaustedException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RetryExecutorTest {

    private final List<Duration> sleeps = new ArrayList<>();
    private final RetryExecutor executor = new RetryExecutor(sleeps::add);

    private RetryProperties retry(int attempts) {
        RetryProperties properties = new RetryProperties();
        properties.setMaxAttempts(attempts);
        properties.setBackoff(Duration.ofMillis(50));
        properties.setMultiplier(3.0);
        return properties;
    }

    @Test
    void thanhCongNgayLanDauKhongChoDoi() {
        assertThat(executor.execute("việc", retry(3), () -> "ok")).isEqualTo("ok");
        assertThat(sleeps).isEmpty();
    }

    @Test
    void thatBaiHetLanThuNemRetryExhaustedVoiCauseGoc() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute("mở DB", retry(3), () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("down");
        })).isInstanceOfSatisfying(RetryExhaustedException.class, e -> {
            assertThat(e.attempts()).isEqualTo(3);
            assertThat(e.getCause()).hasMessage("down");
        });
        assertThat(calls.get()).isEqualTo(3);
        assertThat(sleeps).containsExactly(Duration.ofMillis(50), Duration.ofMillis(150));
    }

    @Test
    void motLanThuKhongChoDoi() {
        assertThatThrownBy(() -> executor.execute("mở DB", retry(1), () -> {
            throw new IllegalStateException("down");
        })).isInstanceOf(RetryExhaustedException.class);
        assertThat(sleeps).isEmpty();
    }
}
