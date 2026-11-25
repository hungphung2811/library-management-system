package lms.sales.oplog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import lms.sales.oplog.application.OperationalLogQueryUseCase;
import lms.sales.oplog.application.OperationalLogQueryUseCase.Filter;
import lms.sales.oplog.application.OperationalLogStore;
import lms.sales.oplog.application.PurgeOperationalLogsUseCase;
import lms.sales.oplog.domain.OperationalLogEvent;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import org.junit.jupiter.api.Test;

class OperationalLogQueryUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private static class Recording implements OperationalLogStore {
        Criteria last;
        int lastPage;
        int lastSize;
        final List<Integer> purgeResults = new ArrayList<>();
        final List<Instant> cutoffs = new ArrayList<>();

        @Override
        public void saveBatch(List<OperationalLogEvent> events) {
        }

        @Override
        public PageResult<OperationalLogEvent> search(Criteria criteria, int page, int size) {
            last = criteria;
            lastPage = page;
            lastSize = size;
            return new PageResult<>(List.of(), 0, page, size);
        }

        @Override
        public int purgeOlderThan(Instant cutoff, int limit) {
            cutoffs.add(cutoff);
            return purgeResults.isEmpty() ? 0 : purgeResults.remove(0);
        }
    }

    private static Filter filter(String channel, String level, Instant from, Instant to) {
        return new Filter(null, channel, "  momo ", " ", level, true, from, to);
    }

    @Test
    void tabTichHopMacDinhGomGoiRaVaGoiVaoVaMacDinhBayNgay() {
        Recording store = new Recording();
        new OperationalLogQueryUseCase(store, clock).integration(filter(null, null, null, null), 0, 20);
        assertThat(store.last.channels()).containsExactly("INTEGRATION_OUTBOUND", "INTEGRATION_INBOUND");
        assertThat(store.last.to()).isEqualTo(NOW);
        assertThat(store.last.from()).isEqualTo(NOW.minus(Duration.ofDays(7)));
        assertThat(store.last.provider()).isEqualTo("momo");
        assertThat(store.last.outcome()).isNull();
        assertThat(store.last.anomalyOnly()).isTrue();
    }

    @Test
    void locTheoKenhVaTabHeThongChiTraSystem() {
        Recording store = new Recording();
        OperationalLogQueryUseCase uc = new OperationalLogQueryUseCase(store, clock);
        uc.integration(filter("inbound", "warn", null, null), 0, 20);
        assertThat(store.last.channels()).containsExactly("INTEGRATION_INBOUND");
        assertThat(store.last.level()).isEqualTo("WARN");
        uc.system(filter("OUTBOUND", null, null, null), 0, 20);
        assertThat(store.last.channels()).containsExactly("SYSTEM");
        assertThatThrownBy(() -> uc.integration(filter("SYSTEM", null, null, null), 0, 20))
                .isInstanceOfSatisfying(SalesException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> uc.integration(filter(null, "LOUD", null, null), 0, 20))
                .isInstanceOf(SalesException.class);
    }

    @Test
    void khoangThoiGianToiDa31NgayVaFromTruocTo() {
        Recording store = new Recording();
        OperationalLogQueryUseCase uc = new OperationalLogQueryUseCase(store, clock);
        uc.integration(filter(null, null, NOW.minus(Duration.ofDays(31)), NOW), 0, 20);
        assertThatThrownBy(() -> uc.integration(filter(null, null, NOW.minus(Duration.ofDays(31)).minusSeconds(1), NOW), 0, 20))
                .isInstanceOfSatisfying(SalesException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_RANGE));
        assertThatThrownBy(() -> uc.system(filter(null, null, NOW, NOW), 0, 20))
                .isInstanceOfSatisfying(SalesException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_RANGE));
        // chỉ có from xa hơn 31 ngày so với now: từ chối
        assertThatThrownBy(() -> uc.system(filter(null, null, NOW.minus(Duration.ofDays(40)), null), 0, 20))
                .isInstanceOf(SalesException.class);
    }

    @Test
    void kichThuocTrangBiChan100() {
        Recording store = new Recording();
        new OperationalLogQueryUseCase(store, clock).system(filter(null, null, null, null), -3, 5000);
        assertThat(store.lastSize).isEqualTo(100);
        assertThat(store.lastPage).isZero();
    }

    @Test
    void donDepXoaTheoLoCho30NgayCuVaDungKhiLoCuoiChuaDay() {
        Recording store = new Recording();
        store.purgeResults.addAll(List.of(10, 10, 4));
        int total = new PurgeOperationalLogsUseCase(store, clock, 30, 10).execute();
        assertThat(total).isEqualTo(24);
        assertThat(store.cutoffs).hasSize(3).containsOnly(NOW.minus(Duration.ofDays(30)));
    }
}
