package lms.circulation.reporting.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.circulation.reporting.application.BorrowingTrendResponse;
import lms.circulation.reporting.application.GetBorrowingTrendUseCase;
import lms.circulation.reporting.application.GetLendingSummaryUseCase;
import lms.circulation.reporting.application.GetTopBorrowedTitlesUseCase;
import lms.circulation.reporting.application.LendingStatsPort;
import lms.circulation.reporting.application.LendingSummaryResponse;
import lms.circulation.reporting.domain.DateRange;
import lms.circulation.reporting.domain.InvalidReportQueryException;
import lms.circulation.reporting.domain.LendingCounts;
import lms.circulation.reporting.domain.OverdueCopy;
import lms.circulation.reporting.domain.TopTitle;
import lms.circulation.reporting.domain.UnpaidFines;
import org.junit.jupiter.api.Test;

/** Use case thống kê mượn trả với cổng đọc giả (không cần Docker), gồm biên múi giờ Asia/Ho_Chi_Minh. */
class ReportingUseCasesTest {

    static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Cổng đọc giả ghi lại tham số nhận được. */
    static class FakeStats implements LendingStatsPort {
        LocalDate countsToday;
        DateRange lastRange;
        int lastLimit;
        Map<LocalDate, Long> borrowed = Map.of();
        Map<LocalDate, Long> returned = Map.of();
        List<TopTitle> top = List.of();

        public LendingCounts counts(LocalDate today) {
            countsToday = today;
            return new LendingCounts(7, 3, 5, 2, 1);
        }
        public UnpaidFines unpaidFines() { return new UnpaidFines(4, new BigDecimal("125000.00")); }
        public Map<LocalDate, Long> borrowedPerDay(DateRange range) { lastRange = range; return borrowed; }
        public Map<LocalDate, Long> returnedPerDay(DateRange range) { return returned; }
        public List<TopTitle> topBorrowedTitles(DateRange range, int limit) { lastRange = range; lastLimit = limit; return top; }
        public long countOverdueCopies(LocalDate today) { return 0; }
        public List<OverdueCopy> overdueCopies(LocalDate today, int offset, int limit) { return List.of(); }
    }

    static Clock clockAt(String utcInstant) {
        return Clock.fixed(Instant.parse(utcInstant), VN);
    }

    // ---- summary + biên múi giờ (AC5) ----

    @Test
    void homNayTheoGioVn2330CuaNgayD() {
        FakeStats stats = new FakeStats();
        // 23:30 giờ VN ngày 2026-03-01 = 16:30 UTC cùng ngày
        LendingSummaryResponse r = new GetLendingSummaryUseCase(stats, clockAt("2026-03-01T16:30:00Z")).execute(null);

        assertThat(r.asOf()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(stats.countsToday).isEqualTo(LocalDate.of(2026, 3, 1));
    }

    @Test
    void homNayTheoGioVn0010CuaNgayDCong1() {
        FakeStats stats = new FakeStats();
        // 00:10 giờ VN ngày 2026-03-02 = 17:10 UTC ngày 2026-03-01
        LendingSummaryResponse r = new GetLendingSummaryUseCase(stats, clockAt("2026-03-01T17:10:00Z")).execute(null);

        assertThat(r.asOf()).isEqualTo(LocalDate.of(2026, 3, 2));
        assertThat(stats.countsToday).isEqualTo(LocalDate.of(2026, 3, 2));
    }

    @Test
    void summaryTraDuSoLieuVaMuiGio() {
        LendingSummaryResponse r = new GetLendingSummaryUseCase(new FakeStats(), clockAt("2026-03-01T05:00:00Z")).execute(null);

        assertThat(r.timezone()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(r.lending()).isEqualTo(new LendingSummaryResponse.Lending(7, 3, 5, 2, 1));
        assertThat(r.fines().unpaidCount()).isEqualTo(4);
        assertThat(r.fines().unpaidAmount()).isEqualByComparingTo("125000");
    }

    @Test
    void asOfTuongMinhGhiDeLenDongHo() {
        FakeStats stats = new FakeStats();
        LendingSummaryResponse r = new GetLendingSummaryUseCase(stats, clockAt("2026-03-01T05:00:00Z"))
                .execute(LocalDate.of(2026, 1, 15));

        assertThat(r.asOf()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(stats.countsToday).isEqualTo(LocalDate.of(2026, 1, 15));
    }

    // ---- biểu đồ ----

    private GetBorrowingTrendUseCase trend(FakeStats stats) {
        return new GetBorrowingTrendUseCase(stats, clockAt("2026-03-01T05:00:00Z")); // 12:00 VN, 2026-03-01 (Chủ nhật)
    }

    @Test
    void theoNgayTra30PhanTuDuMocKhongVaThuTuTang() {
        FakeStats stats = new FakeStats();
        LocalDate d = LocalDate.of(2026, 3, 1);
        stats.borrowed = Map.of(d, 3L, d.minusDays(29), 1L);
        stats.returned = Map.of(d, 2L);

        BorrowingTrendResponse r = trend(stats).execute(d.minusDays(29), d, "day");

        assertThat(r.granularity()).isEqualTo("day");
        assertThat(r.points()).hasSize(30);
        assertThat(r.points().get(0).bucket()).isEqualTo(d.minusDays(29));
        assertThat(r.points().get(0).borrowed()).isEqualTo(1);
        assertThat(r.points().get(29)).isEqualTo(new BorrowingTrendResponse.Point(d, 3, 2));
        assertThat(r.points().subList(1, 29)).allMatch(p -> p.borrowed() == 0 && p.returned() == 0);
        List<LocalDate> buckets = new ArrayList<>(r.points().stream().map(BorrowingTrendResponse.Point::bucket).toList());
        assertThat(buckets).isSorted();
    }

    @Test
    void macDinh30NgayGanNhatTinhToiHomNayGioVn() {
        FakeStats stats = new FakeStats();

        BorrowingTrendResponse r = trend(stats).execute(null, null, null);

        assertThat(r.to()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(r.from()).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(r.granularity()).isEqualTo("day");
        assertThat(r.points()).hasSize(30);
    }

    @Test
    void theoTuanBucketLaThuHaiIsoVaGopSoNgay() {
        FakeStats stats = new FakeStats();
        // 2026-02-23 là thứ Hai. Khoảng 2026-02-25 (thứ Tư) tới 2026-03-09 (thứ Hai) gồm 3 tuần: 02-23, 03-02, 03-09
        stats.borrowed = Map.of(LocalDate.of(2026, 2, 25), 2L, LocalDate.of(2026, 3, 1), 4L,
                LocalDate.of(2026, 3, 2), 1L, LocalDate.of(2026, 3, 9), 5L);
        stats.returned = Map.of(LocalDate.of(2026, 3, 8), 3L);

        BorrowingTrendResponse r = trend(stats).execute(LocalDate.of(2026, 2, 25), LocalDate.of(2026, 3, 9), "week");

        assertThat(r.granularity()).isEqualTo("week");
        assertThat(r.points()).containsExactly(
                new BorrowingTrendResponse.Point(LocalDate.of(2026, 2, 23), 6, 0),
                new BorrowingTrendResponse.Point(LocalDate.of(2026, 3, 2), 1, 3),
                new BorrowingTrendResponse.Point(LocalDate.of(2026, 3, 9), 5, 0));
        assertThat(r.points()).allMatch(p -> p.bucket().getDayOfWeek() == java.time.DayOfWeek.MONDAY);
    }

    @Test
    void ngayNgoaiKhoangBiBoQua() {
        FakeStats stats = new FakeStats();
        stats.borrowed = Map.of(LocalDate.of(2026, 1, 1), 99L, LocalDate.of(2026, 3, 1), 1L);

        BorrowingTrendResponse r = trend(stats).execute(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 1), "day");

        assertThat(r.points()).extracting(BorrowingTrendResponse.Point::borrowed).containsExactly(0L, 1L);
    }

    @Test
    void granularityKhongHopLeBi400InvalidGranularity() {
        assertThatThrownBy(() -> trend(new FakeStats()).execute(null, null, "month"))
                .isInstanceOf(InvalidReportQueryException.class)
                .extracting(e -> ((InvalidReportQueryException) e).code()).isEqualTo("INVALID_GRANULARITY");
    }

    @Test
    void fromSauToBiInvalidRange() {
        assertThatThrownBy(() -> trend(new FakeStats()).execute(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 1), "day"))
                .isInstanceOf(InvalidReportQueryException.class)
                .extracting(e -> ((InvalidReportQueryException) e).code()).isEqualTo("INVALID_RANGE");
    }

    @Test
    void khoang400NgayBiRangeTooLargeCon366NgayThiDuoc() {
        LocalDate to = LocalDate.of(2026, 3, 1);
        assertThatThrownBy(() -> trend(new FakeStats()).execute(to.minusDays(399), to, "day"))
                .isInstanceOf(InvalidReportQueryException.class)
                .extracting(e -> ((InvalidReportQueryException) e).code()).isEqualTo("RANGE_TOO_LARGE");
        assertThat(trend(new FakeStats()).execute(to.minusDays(365), to, "day").points()).hasSize(366);
        assertThatThrownBy(() -> trend(new FakeStats()).execute(to.minusDays(366), to, "day"))
                .isInstanceOf(InvalidReportQueryException.class);
    }

    // ---- top sách mượn ----

    @Test
    void topMacDinhLimit10VaChuyenKhoangXuongCong() {
        FakeStats stats = new FakeStats();
        UUID t1 = UUID.randomUUID();
        stats.top = List.of(new TopTitle(t1, 9));
        GetTopBorrowedTitlesUseCase useCase = new GetTopBorrowedTitlesUseCase(stats, clockAt("2026-03-01T05:00:00Z"));

        var r = useCase.execute(null, null, null);

        assertThat(stats.lastLimit).isEqualTo(10);
        assertThat(stats.lastRange).isEqualTo(new DateRange(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 3, 1)));
        assertThat(r.items()).containsExactly(new lms.circulation.reporting.application.TopBorrowedTitlesResponse.Item(t1, 9));
    }

    @Test
    void limitNgoai1Den50Bi400() {
        GetTopBorrowedTitlesUseCase useCase = new GetTopBorrowedTitlesUseCase(new FakeStats(), clockAt("2026-03-01T05:00:00Z"));
        for (int bad : new int[]{0, -1, 51}) {
            assertThatThrownBy(() -> useCase.execute(null, null, bad))
                    .isInstanceOf(InvalidReportQueryException.class)
                    .extracting(e -> ((InvalidReportQueryException) e).code()).isEqualTo("INVALID_LIMIT");
        }
        assertThat(useCase.execute(null, null, 1).limit()).isEqualTo(1);
        assertThat(useCase.execute(null, null, 50).limit()).isEqualTo(50);
    }

    // ---- định nghĩa quá hạn ----

    @Test
    void soNgayQuaHanTinhTuDueDateToiHomNay() {
        OverdueCopy c = new OverdueCopy(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 15));

        assertThat(c.daysOverdue(LocalDate.of(2026, 2, 16))).isEqualTo(1);
        assertThat(c.daysOverdue(LocalDate.of(2026, 3, 1))).isEqualTo(14);
    }
}
