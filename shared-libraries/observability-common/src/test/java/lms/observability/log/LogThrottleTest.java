package lms.observability.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import lms.observability.log.LogThrottle.Decision;
import org.junit.jupiter.api.Test;

class LogThrottleTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-19T00:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    private final MutableClock clock = new MutableClock();

    @Test
    void lanDauPhatCacLanSauTrongCuaSoBiNuotVaLanKeTiepBaoSoDong() {
        LogThrottle t = new LogThrottle(clock, Duration.ofSeconds(60), 100);
        assertThat(t.acquire("k")).isEqualTo(new Decision(true, 0));
        for (int i = 0; i < 9; i++) {
            clock.advance(Duration.ofSeconds(1));
            assertThat(t.acquire("k").emit()).isFalse();
        }
        clock.advance(Duration.ofSeconds(60));
        assertThat(t.acquire("k")).isEqualTo(new Decision(true, 9));
        assertThat(t.acquire("k").emit()).isFalse();
    }

    @Test
    void khoaKhacNhauKhongAnhHuongNhau() {
        LogThrottle t = new LogThrottle(clock, Duration.ofSeconds(60), 100);
        assertThat(t.acquire("a").emit()).isTrue();
        assertThat(t.acquire("b").emit()).isTrue();
        assertThat(t.acquire("a").emit()).isFalse();
    }

    @Test
    void cuaSoRiengChoTungLanGoi() {
        LogThrottle t = new LogThrottle(clock, Duration.ofSeconds(60), 100);
        assertThat(t.acquire("x", "x", Duration.ofMinutes(10)).emit()).isTrue();
        clock.advance(Duration.ofMinutes(5));
        assertThat(t.acquire("x", "x", Duration.ofMinutes(10)).emit()).isFalse();
        clock.advance(Duration.ofMinutes(5));
        assertThat(t.acquire("x", "x", Duration.ofMinutes(10))).isEqualTo(new Decision(true, 1));
    }

    @Test
    void vuotGioiHanKhoaThiGopVeKhoaDuPhongVaBoNhoKhongTang() {
        LogThrottle t = new LogThrottle(clock, Duration.ofSeconds(60), 3);
        for (int i = 0; i < 3; i++) {
            assertThat(t.acquire("T:" + i, "T", Duration.ofSeconds(60)).emit()).isTrue();
        }
        // khóa thứ 4 trở đi gộp về "T" (khóa dự phòng): lần đầu phát, các lần sau bị nuốt
        assertThat(t.acquire("T:3", "T", Duration.ofSeconds(60)).emit()).isTrue();
        for (int i = 4; i < 1000; i++) {
            assertThat(t.acquire("T:" + i, "T", Duration.ofSeconds(60)).emit()).isFalse();
        }
        assertThat(t.size()).isLessThanOrEqualTo(4);
    }

    @Test
    void khoaHetHanDuocDonKhiDayDeNhuongCho() {
        LogThrottle t = new LogThrottle(clock, Duration.ofSeconds(60), 2);
        t.acquire("a");
        t.acquire("b");
        clock.advance(Duration.ofSeconds(120));
        assertThat(t.acquire("c").emit()).isTrue();
        assertThat(t.size()).isEqualTo(1);
    }
}
