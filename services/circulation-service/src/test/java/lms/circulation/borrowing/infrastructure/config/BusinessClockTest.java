package lms.circulation.borrowing.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** Bean Clock thật của circulation phải theo múi giờ nghiệp vụ, không theo múi giờ máy (container là UTC). */
class BusinessClockTest {

    @Test
    void beanClockLaAsiaHoChiMinh() {
        Clock clock = new BorrowingBeanConfiguration().clock();

        assertThat(clock.getZone()).isEqualTo(ZoneId.of("Asia/Ho_Chi_Minh"));
        assertThat(BorrowingBeanConfiguration.BUSINESS_ZONE).isEqualTo("Asia/Ho_Chi_Minh");
    }

    @Test
    void cungMotThoiDiemUtcDoiNgayTheoGioVn() {
        ZoneId zone = new BorrowingBeanConfiguration().clock().getZone();

        // 16:30Z = 23:30 giờ VN ngày 01; 17:10Z = 00:10 giờ VN ngày 02 (UTC vẫn là ngày 01)
        assertThat(LocalDate.now(Clock.fixed(Instant.parse("2026-03-01T16:30:00Z"), zone))).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(LocalDate.now(Clock.fixed(Instant.parse("2026-03-01T17:10:00Z"), zone))).isEqualTo(LocalDate.of(2026, 3, 2));
    }
}
