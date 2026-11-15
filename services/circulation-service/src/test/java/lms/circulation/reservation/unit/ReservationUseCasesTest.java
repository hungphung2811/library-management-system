package lms.circulation.reservation.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import lms.circulation.reservation.application.CancelReservationUseCase;
import lms.circulation.reservation.application.ExpireOverdueReservationsUseCase;
import lms.circulation.reservation.application.HoldCopyForNextReservationUseCase;
import lms.circulation.reservation.application.ListMyReservationsUseCase;
import lms.circulation.reservation.application.ReaderEligibilityPort;
import lms.circulation.reservation.application.ReservationResponse;
import lms.circulation.reservation.application.ReserveTitleUseCase;
import lms.circulation.reservation.application.TitleCatalogPort;
import lms.circulation.reservation.domain.AlreadyReservedException;
import lms.circulation.reservation.domain.CopyStillAvailableException;
import lms.circulation.reservation.domain.ReaderNotEligibleException;
import lms.circulation.reservation.domain.Reservation;
import lms.circulation.reservation.domain.ReservationNotFoundException;
import lms.circulation.reservation.domain.ReservationRepository;
import lms.circulation.reservation.domain.ReservationStatus;
import lms.circulation.reservation.domain.TitleNotFoundException;
import lms.common.kernel.DomainEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReservationUseCasesTest {

    /** Repository trong bộ nhớ, vị trí hàng đợi tính theo (reservedAt, id) giống cài đặt thật. */
    static class FakeRepo implements ReservationRepository {
        final List<Reservation> items = new ArrayList<>();

        public Reservation save(Reservation r) {
            items.removeIf(x -> x.id().equals(r.id()));
            items.add(r);
            return r;
        }
        public Optional<Reservation> findById(UUID id) { return items.stream().filter(r -> r.id().equals(id)).findFirst(); }
        public boolean existsActive(UUID reader, UUID title) {
            return items.stream().anyMatch(r -> r.readerId().equals(reader) && r.titleId().equals(title) && r.isActive());
        }
        public List<Reservation> findByReader(UUID reader) {
            return items.stream().filter(r -> r.readerId().equals(reader))
                    .sorted(Comparator.comparing(Reservation::reservedAt).reversed()).toList();
        }
        public Optional<Reservation> findNextWaitingForUpdate(UUID titleId) {
            return items.stream().filter(r -> r.titleId().equals(titleId) && r.status() == ReservationStatus.WAITING)
                    .min(Comparator.comparing(Reservation::reservedAt));
        }
        public List<Reservation> findHoldExpired(java.time.LocalDate today) {
            return items.stream().filter(r -> r.isHoldExpired(today)).toList();
        }
        public boolean existsReadyForCopy(UUID copyId) {
            return items.stream().anyMatch(r -> r.status() == ReservationStatus.READY && copyId.equals(r.copyId()));
        }
        public Optional<Reservation> findReadyByCopyId(UUID copyId) {
            return items.stream().filter(r -> r.status() == ReservationStatus.READY && copyId.equals(r.copyId())).findFirst();
        }
        public long queuePosition(Reservation r) {
            if (r.status() != ReservationStatus.WAITING) return 0;
            return items.stream().filter(x -> x.titleId().equals(r.titleId()) && x.status() == ReservationStatus.WAITING
                    && x.reservedAt().compareTo(r.reservedAt()) <= 0).count();
        }
    }

    final UUID reader = UUID.randomUUID();
    final UUID title = UUID.randomUUID();
    final FakeRepo repo = new FakeRepo();
    final List<DomainEvent> published = new ArrayList<>();
    ReaderEligibilityPort.ReaderEligibility eligibility = new ReaderEligibilityPort.ReaderEligibility(true, true);
    long availableCopies = 0;
    boolean titleExists = true;
    Instant now = Instant.parse("2026-10-01T08:00:00Z");

    ReserveTitleUseCase reserve;
    CancelReservationUseCase cancel;
    HoldCopyForNextReservationUseCase holdCopy;
    ExpireOverdueReservationsUseCase expire;

    @BeforeEach
    void setUp() {
        Clock clock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return now; }
        };
        ReaderEligibilityPort eligibilityPort = id -> eligibility;
        TitleCatalogPort catalogPort = id -> titleExists
                ? Optional.of(new TitleCatalogPort.TitleInfo(id, "Clean Code", availableCopies)) : Optional.empty();
        reserve = new ReserveTitleUseCase(eligibilityPort, catalogPort, repo, published::add, clock);
        holdCopy = new HoldCopyForNextReservationUseCase(repo, published::add, clock);
        cancel = new CancelReservationUseCase(repo, holdCopy, published::add, clock);
        expire = new ExpireOverdueReservationsUseCase(repo, holdCopy, published::add, clock);
    }

    @Test
    void datTruocThanhCongXepViTriDauHangVaPhatEvent() {
        ReservationResponse r = reserve.execute(reader, title);
        assertEquals(ReservationStatus.WAITING, r.status());
        assertEquals(1, r.queuePosition());
        assertEquals(1, published.size());
    }

    @Test
    void nguoiSauXepCuoiHangDoiFifo() {
        reserve.execute(reader, title);
        now = now.plusSeconds(5);
        ReservationResponse second = reserve.execute(UUID.randomUUID(), title);
        assertEquals(2, second.queuePosition());
    }

    @Test
    void tuChoiKhiTheKhongHopLeHoacChuaCoThe() {
        eligibility = new ReaderEligibilityPort.ReaderEligibility(true, false);
        assertThrows(ReaderNotEligibleException.class, () -> reserve.execute(reader, title));
        eligibility = new ReaderEligibilityPort.ReaderEligibility(false, false);
        assertThrows(ReaderNotEligibleException.class, () -> reserve.execute(reader, title));
        assertTrue(repo.items.isEmpty());
    }

    @Test
    void tuChoiKhiDauSachKhongTonTaiHoacConBanSao() {
        titleExists = false;
        assertThrows(TitleNotFoundException.class, () -> reserve.execute(reader, title));
        titleExists = true;
        availableCopies = 1;
        assertThrows(CopyStillAvailableException.class, () -> reserve.execute(reader, title));
        assertTrue(repo.items.isEmpty());
    }

    @Test
    void tuChoiDatTruocTrungKhiConHieuLucNhungChoDatLaiSauKhiHuy() {
        ReservationResponse first = reserve.execute(reader, title);
        assertThrows(AlreadyReservedException.class, () -> reserve.execute(reader, title));

        cancel.execute(reader, first.id());
        ReservationResponse again = reserve.execute(reader, title);
        assertEquals(1, again.queuePosition());
    }

    @Test
    void huyDatTruocCuaNguoiKhacBiTuChoiNhuKhongTonTai() {
        ReservationResponse r = reserve.execute(reader, title);
        assertThrows(ReservationNotFoundException.class, () -> cancel.execute(UUID.randomUUID(), r.id()));
        assertThrows(ReservationNotFoundException.class, () -> cancel.execute(reader, UUID.randomUUID()));
    }

    @Test
    void danhSachCuaToiKemViTriVaNguoiSauDuocLenHangKhiCoNguoiHuy() {
        ReservationResponse first = reserve.execute(reader, title);
        now = now.plusSeconds(5);
        UUID other = UUID.randomUUID();
        ReservationResponse second = reserve.execute(other, title);
        assertEquals(2, second.queuePosition());

        cancel.execute(reader, first.id());
        ListMyReservationsUseCase list = new ListMyReservationsUseCase(repo);
        assertEquals(1, list.execute(other).get(0).queuePosition());
        assertEquals(0, list.execute(reader).get(0).queuePosition());
        assertEquals(ReservationStatus.CANCELLED, list.execute(reader).get(0).status());
    }

    // ---- QĐ6, QĐ9, UC14 ----

    @Test
    void banSaoSanSangDuocGiuChoNguoiDauHangTrong3Ngay() {
        ReservationResponse first = reserve.execute(reader, title);
        now = now.plusSeconds(5);
        reserve.execute(UUID.randomUUID(), title);
        UUID copy = UUID.randomUUID();

        assertTrue(holdCopy.holdIfAnyoneWaiting(title, copy));

        Reservation r = repo.findById(first.id()).orElseThrow();
        assertEquals(ReservationStatus.READY, r.status());
        assertEquals(copy, r.copyId());
        assertEquals(java.time.LocalDate.of(2026, 10, 4), r.holdUntil());
        // idempotent: event giao lại không giữ thêm cho người thứ hai
        assertTrue(holdCopy.holdIfAnyoneWaiting(title, copy));
        assertEquals(1, repo.items.stream().filter(x -> x.status() == ReservationStatus.READY).count());
    }

    @Test
    void khongCoNguoiChoThiKhongGiu() {
        assertEquals(false, holdCopy.holdIfAnyoneWaiting(title, UUID.randomUUID()));
    }

    @Test
    void hetHanGiuChuyenChoNguoiKeTiepHoacTraVeKho() {
        ReservationResponse first = reserve.execute(reader, title);
        now = now.plusSeconds(5);
        ReservationResponse second = reserve.execute(UUID.randomUUID(), title);
        UUID copy = UUID.randomUUID();
        holdCopy.holdIfAnyoneWaiting(title, copy);

        // hạn giữ là ngày 04/10; đến hết ngày đó vẫn chưa xử lý
        now = Instant.parse("2026-10-04T08:00:00Z");
        assertEquals(0, expire.execute());

        // qua ngày 05/10: người đầu EXPIRED, bản sao chuyển cho người kế tiếp (hạn mới +3 ngày)
        now = Instant.parse("2026-10-05T00:10:00Z");
        assertEquals(1, expire.execute());
        assertEquals(ReservationStatus.EXPIRED, repo.findById(first.id()).orElseThrow().status());
        Reservation next = repo.findById(second.id()).orElseThrow();
        assertEquals(ReservationStatus.READY, next.status());
        assertEquals(copy, next.copyId());
        assertEquals(java.time.LocalDate.of(2026, 10, 8), next.holdUntil());

        // người kế tiếp cũng hết hạn, không còn ai chờ: phát CopyReleasedEvent để catalog trả về kho
        published.clear();
        now = Instant.parse("2026-10-09T00:10:00Z");
        assertEquals(1, expire.execute());
        assertTrue(published.stream().anyMatch(e -> e instanceof lms.circulation.reservation.domain.CopyReleasedEvent));
    }

    @Test
    void huyDatTruocReadyNhaBanSaoChoNguoiKeTiep() {
        ReservationResponse first = reserve.execute(reader, title);
        now = now.plusSeconds(5);
        ReservationResponse second = reserve.execute(UUID.randomUUID(), title);
        UUID copy = UUID.randomUUID();
        holdCopy.holdIfAnyoneWaiting(title, copy);

        cancel.execute(reader, first.id());

        Reservation next = repo.findById(second.id()).orElseThrow();
        assertEquals(ReservationStatus.READY, next.status());
        assertEquals(copy, next.copyId());
    }

    @Test
    void huyDatTruocReadyKhongCoAiChoThiTraBanSaoVeKho() {
        ReservationResponse first = reserve.execute(reader, title);
        holdCopy.holdIfAnyoneWaiting(title, UUID.randomUUID());
        published.clear();

        cancel.execute(reader, first.id());

        assertTrue(published.stream().anyMatch(e -> e instanceof lms.circulation.reservation.domain.CopyReleasedEvent));
    }
}
