package lms.circulation.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lms.circulation.borrowing.application.SaveBorrowingPort;
import lms.circulation.borrowing.domain.BorrowingId;
import lms.circulation.borrowing.domain.BorrowingItem;
import lms.circulation.borrowing.domain.BorrowingRecord;
import lms.circulation.borrowing.domain.BorrowingRepository;
import lms.circulation.borrowing.infrastructure.demo.BorrowingDemoSeedStep;
import lms.circulation.fine.domain.Fine;
import lms.circulation.fine.domain.FineReason;
import lms.circulation.fine.domain.FineRepository;
import lms.circulation.fine.domain.FineStatus;
import lms.circulation.fine.infrastructure.demo.FineDemoSeedStep;
import lms.circulation.reservation.domain.Reservation;
import lms.circulation.reservation.domain.ReservationRepository;
import lms.circulation.reservation.domain.ReservationStatus;
import lms.circulation.reservation.infrastructure.demo.ReservationDemoSeedStep;
import lms.demo.plan.DemoPlan;
import lms.demo.plan.DemoPlan.CopyStatus;
import lms.demo.runner.DemoSeedContext;
import lms.demo.runner.DemoSeedStep.SeedResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Bước seed circulation chạy trên kho giả trong bộ nhớ: idempotent, bất biến 6 quá hạn / 4 phạt chưa thu, khớp catalog. */
class CirculationDemoSeedTest {

    static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    /** Kho giả: cùng hành vi findById/exists/save của adapter thật (không có RLS). */
    static final class Stores {
        final Map<UUID, BorrowingRecord> borrowings = new HashMap<>();
        final Map<UUID, Fine> fines = new HashMap<>();
        final Map<UUID, Reservation> reservations = new HashMap<>();
        final Set<UUID> openCopies = new HashSet<>();
        final BorrowingDemoSeedStep borrowingStep;
        final FineDemoSeedStep fineStep;
        final ReservationDemoSeedStep reservationStep;

        Stores() {
            BorrowingRepository repo = mock(BorrowingRepository.class);
            when(repo.findById(any())).thenAnswer(i -> Optional.ofNullable(borrowings.get(((BorrowingId) i.getArgument(0)).value())));
            SaveBorrowingPort save = mock(SaveBorrowingPort.class);
            when(save.save(any())).thenAnswer(i -> {
                BorrowingRecord r = i.getArgument(0);
                // uq_borrowing_items_open_copy: bản sao không thuộc hai lượt mượn đang mở
                for (BorrowingItem item : r.items()) {
                    if (!item.isReturned() && !openCopies.add(item.bookCopyId())) {
                        throw new IllegalStateException("uq_borrowing_items_open_copy " + item.bookCopyId());
                    }
                }
                borrowings.put(r.id().value(), r);
                return r;
            });
            FineRepository fineRepo = mock(FineRepository.class);
            when(fineRepo.exists(any(), any(), any())).thenAnswer(i -> fines.values().stream().anyMatch(f ->
                    f.borrowingId().equals(i.getArgument(0)) && f.bookCopyId().equals(i.getArgument(1))
                            && f.reason() == i.getArgument(2)));
            when(fineRepo.save(any())).thenAnswer(i -> {
                Fine f = i.getArgument(0);
                fines.put(f.id(), f);
                return f;
            });
            ReservationRepository resRepo = mock(ReservationRepository.class);
            when(resRepo.findById(any())).thenAnswer(i -> Optional.ofNullable(reservations.get(i.<UUID>getArgument(0))));
            when(resRepo.save(any())).thenAnswer(i -> {
                Reservation r = i.getArgument(0);
                reservations.put(r.id(), r);
                return r;
            });
            borrowingStep = new BorrowingDemoSeedStep(repo, save);
            fineStep = new FineDemoSeedStep(fineRepo);
            reservationStep = new ReservationDemoSeedStep(resRepo);
        }

        List<SeedResult> runAll(DemoSeedContext ctx) {
            return List.of(borrowingStep.run(ctx), fineStep.run(ctx), reservationStep.run(ctx));
        }
    }

    static Clock clock(LocalDate day) {
        return Clock.fixed(day.atTime(10, 30).atZone(VN).toInstant(), VN);
    }

    static DemoSeedContext context(LocalDate today, long seed) {
        return new DemoSeedContext(TENANT, DemoPlan.generate(today, seed), "x", clock(today));
    }

    @Test
    void thuTuCacBuocLaPhieuRoiPhatRoiDatTruoc() {
        Stores s = new Stores();
        assertThat(s.borrowingStep.order()).isLessThan(s.fineStep.order());
        assertThat(s.fineStep.order()).isLessThan(s.reservationStep.order());
    }

    @Test
    void chayLanDauTaoDungSoLuongTheoKeHoach() {
        DemoSeedContext ctx = context(LocalDate.of(2026, 11, 17), DemoPlan.DEFAULT_SEED);
        Stores s = new Stores();

        List<SeedResult> r = s.runAll(ctx);

        assertThat(r.get(0)).isEqualTo(SeedResult.of(ctx.plan().borrowings().size(), 0));
        assertThat(r.get(1)).isEqualTo(SeedResult.of(ctx.plan().fines().size(), 0));
        assertThat(r.get(2)).isEqualTo(SeedResult.of(
                ctx.plan().reservations().size() + ctx.plan().workReservations().size(), 0));
        assertThat(s.borrowings).hasSizeGreaterThanOrEqualTo(120);
    }

    @Test
    void chayLaiKhongNhanDoiKhongLoi() {
        DemoSeedContext ctx = context(LocalDate.of(2026, 11, 17), DemoPlan.DEFAULT_SEED);
        Stores s = new Stores();
        s.runAll(ctx);
        int b = s.borrowings.size();
        int f = s.fines.size();
        int r = s.reservations.size();

        List<SeedResult> again = s.runAll(ctx);

        assertThat(again.get(0)).isEqualTo(SeedResult.of(0, b));
        assertThat(again.get(1)).isEqualTo(SeedResult.of(0, f));
        assertThat(again.get(2)).isEqualTo(SeedResult.of(0, r));
        assertThat(s.borrowings).hasSize(b);
        assertThat(s.fines).hasSize(f);
        assertThat(s.reservations).hasSize(r);
    }

    @ParameterizedTest
    @ValueSource(longs = {DemoPlan.DEFAULT_SEED, 1L, 42L})
    void batBienDungSauPhieuQuaHanVaBonPhatChuaThu(long seed) {
        LocalDate today = LocalDate.of(2026, 11, 17);
        Stores s = new Stores();
        s.runAll(context(today, seed));

        long overdue = s.borrowings.values().stream().filter(b -> b.isOverdue(today)).count();
        List<Fine> unpaid = s.fines.values().stream().filter(Fine::isUnpaid).toList();

        assertThat(overdue).isEqualTo(DemoPlan.OVERDUE_BORROWINGS);
        assertThat(unpaid).hasSize(DemoPlan.UNPAID_FINES);
        assertThat(unpaid.stream().map(Fine::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(BigDecimal.valueOf(DemoPlan.UNPAID_FINES_TOTAL));
        assertThat(s.fines.values().stream().filter(f -> f.status() == FineStatus.PAID).count()).isGreaterThanOrEqualTo(6);
    }

    @Test
    void haiPhieuBienDenHanHomNayKhongQuaHanVaHanHomQuaQuaHan() {
        LocalDate today = LocalDate.of(2026, 11, 17);
        Stores s = new Stores();
        s.runAll(context(today, DemoPlan.DEFAULT_SEED));

        List<BorrowingRecord> active = s.borrowings.values().stream()
                .filter(b -> b.status() == lms.circulation.borrowing.domain.BorrowingStatus.ACTIVE).toList();

        List<BorrowingRecord> dueToday = active.stream().filter(b -> b.dueDate().equals(today)).toList();
        List<BorrowingRecord> dueYesterday = active.stream().filter(b -> b.dueDate().equals(today.minusDays(1))).toList();
        assertThat(dueToday).isNotEmpty().allMatch(b -> !b.isOverdue(today));
        assertThat(dueYesterday).isNotEmpty().allMatch(b -> b.isOverdue(today));
    }

    @Test
    void dongMuonGhiTitleIdVaBanSaoChuaTraKhopBanSaoLoanedCuaCatalog() {
        DemoSeedContext ctx = context(LocalDate.of(2026, 11, 17), DemoPlan.DEFAULT_SEED);
        Stores s = new Stores();
        s.runAll(ctx);

        Map<UUID, UUID> titleByCopy = ctx.plan().copies().stream()
                .collect(Collectors.toMap(DemoPlan.CopyPlan::id, DemoPlan.CopyPlan::titleId));
        Set<UUID> loaned = ctx.plan().copies().stream().filter(c -> c.status() == CopyStatus.LOANED)
                .map(DemoPlan.CopyPlan::id).collect(Collectors.toSet());
        Set<UUID> open = new HashSet<>();
        for (BorrowingRecord b : s.borrowings.values()) {
            for (BorrowingItem i : b.items()) {
                assertThat(i.titleId()).as("title_id của %s", i.bookCopyId()).isEqualTo(titleByCopy.get(i.bookCopyId()));
                if (!i.isReturned()) open.add(i.bookCopyId());
            }
        }

        assertThat(open).isEqualTo(loaned);
    }

    @Test
    void datTruocKhopBanSaoReservedConHanGiuVaKhongVuotRangBuocUnique() {
        LocalDate today = LocalDate.of(2026, 11, 17);
        DemoSeedContext ctx = context(today, DemoPlan.DEFAULT_SEED);
        Stores s = new Stores();
        s.runAll(ctx);

        Set<UUID> reserved = ctx.plan().copies().stream().filter(c -> c.status() == CopyStatus.RESERVED)
                .map(DemoPlan.CopyPlan::id).collect(Collectors.toSet());
        List<Reservation> all = new ArrayList<>(s.reservations.values());
        List<Reservation> ready = all.stream().filter(r -> r.status() == ReservationStatus.READY).toList();

        assertThat(ready.stream().map(Reservation::copyId).collect(Collectors.toSet())).isEqualTo(reserved);
        assertThat(ready).allMatch(r -> !r.isHoldExpired(today) && !r.holdUntil().isBefore(today));
        // uq_reservations_active: mỗi (độc giả, đầu sách) chỉ một đặt trước còn hiệu lực
        assertThat(all.stream().map(r -> r.readerId() + "/" + r.titleId()).collect(Collectors.toSet())).hasSameSizeAs(all);
        // uq_reservations_active_work: mỗi (độc giả, tác phẩm) chỉ một đặt trước còn hiệu lực
        assertThat(all.stream().map(r -> r.readerId() + "/" + r.workId()).collect(Collectors.toSet())).hasSameSizeAs(all);
    }

    @Test
    void datTruocCoTacPhamNgonNguVaCoDatTruocTheoTacPhamDangCho() {
        DemoSeedContext ctx = context(LocalDate.of(2026, 11, 17), DemoPlan.DEFAULT_SEED);
        Stores s = new Stores();
        s.runAll(ctx);

        Map<UUID, DemoPlan.TitlePlan> titleById = ctx.plan().titles().stream()
                .collect(Collectors.toMap(DemoPlan.TitlePlan::id, t -> t));
        for (DemoPlan.ReservationPlan p : ctx.plan().reservations()) {
            Reservation r = s.reservations.get(p.id());
            assertThat(r.workId()).isEqualTo(titleById.get(p.titleId()).workId());
            assertThat(r.language()).isEqualTo(titleById.get(p.titleId()).language()).isNotNull();
        }
        List<Reservation> waiting = s.reservations.values().stream()
                .filter(r -> r.status() == ReservationStatus.WAITING).toList();
        assertThat(waiting).hasSameSizeAs(ctx.plan().workReservations()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(waiting).allMatch(r -> r.copyId() == null && r.holdUntil() == null);
        assertThat(waiting.stream().map(Reservation::language)).contains("vi", "en").containsNull();
        for (DemoPlan.WorkReservationPlan p : ctx.plan().workReservations()) {
            Reservation r = s.reservations.get(p.id());
            assertThat(r.workId()).isEqualTo(p.workId());
            assertThat(r.titleId()).isEqualTo(p.titleId());
            assertThat(r.language()).isEqualTo(p.language());
            assertThat(r.reservedAt()).isBeforeOrEqualTo(clock(LocalDate.of(2026, 11, 17)).instant());
        }
    }

    @Test
    void dongDatTruocCuChuaCoNgonNguDuocDienMotLanKhongDoiGiKhac() {
        DemoSeedContext ctx = context(LocalDate.of(2026, 11, 17), DemoPlan.DEFAULT_SEED);
        Stores s = new Stores();
        s.runAll(ctx);
        DemoPlan.ReservationPlan p = ctx.plan().reservations().get(0);
        Reservation cur = s.reservations.get(p.id());
        s.reservations.put(p.id(), Reservation.restore(cur.id(), cur.readerId(), cur.titleId(), cur.workId(), null,
                cur.reservedAt(), cur.status(), cur.copyId(), cur.holdUntil()));

        SeedResult first = s.reservationStep.run(ctx);
        Reservation after = s.reservations.get(p.id());
        assertThat(first.created()).isEqualTo(1);
        assertThat(after.language()).isEqualTo(p.language());
        assertThat(after.copyId()).isEqualTo(cur.copyId());
        assertThat(after.holdUntil()).isEqualTo(cur.holdUntil());
        assertThat(after.status()).isEqualTo(ReservationStatus.READY);

        assertThat(s.reservationStep.run(ctx).created()).isZero();
    }

    @Test
    void thoiDiemPhatKhongNamTuongLaiVaThuSauLap() {
        LocalDate today = LocalDate.of(2026, 11, 17);
        Clock clock = clock(today);
        Stores s = new Stores();
        s.runAll(context(today, DemoPlan.DEFAULT_SEED));

        Instant now = clock.instant();
        for (Fine f : s.fines.values()) {
            assertThat(f.createdAt()).isBeforeOrEqualTo(now);
            if (f.paidAt() != null) {
                assertThat(f.paidAt()).isBeforeOrEqualTo(now).isAfterOrEqualTo(f.createdAt());
            }
            assertThat(f.reason()).isEqualTo(FineReason.LATE_RETURN);
        }
        for (Reservation r : s.reservations.values()) assertThat(r.reservedAt()).isBeforeOrEqualTo(now);
    }

    @Test
    void phatTroDenDungBanSaoCuaPhieuDaTra() {
        DemoSeedContext ctx = context(LocalDate.of(2026, 11, 17), DemoPlan.DEFAULT_SEED);
        Stores s = new Stores();
        s.runAll(ctx);

        for (Fine f : s.fines.values()) {
            BorrowingRecord b = s.borrowings.get(f.borrowingId());
            assertThat(b).isNotNull();
            assertThat(b.items()).anyMatch(i -> i.bookCopyId().equals(f.bookCopyId()) && i.isReturned());
        }
    }
}
