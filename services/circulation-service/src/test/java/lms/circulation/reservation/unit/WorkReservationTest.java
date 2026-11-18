package lms.circulation.reservation.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.circulation.reservation.application.HoldCopyForNextReservationUseCase;
import lms.circulation.reservation.application.MoveReservationsToWorkUseCase;
import lms.circulation.reservation.application.ReaderEligibilityPort;
import lms.circulation.reservation.application.ReservationResponse;
import lms.circulation.reservation.application.ReserveTitleUseCase;
import lms.circulation.reservation.application.TitleCatalogPort;
import lms.circulation.reservation.domain.AlreadyReservedException;
import lms.circulation.reservation.domain.CopyReleasedEvent;
import lms.circulation.reservation.domain.CopyStillAvailableException;
import lms.circulation.reservation.domain.LegacyWorkIds;
import lms.circulation.reservation.domain.Reservation;
import lms.circulation.reservation.domain.ReservationCancelledEvent;
import lms.circulation.reservation.domain.ReservationStatus;
import lms.circulation.reservation.infrastructure.messaging.InventoryEventConsumer;
import lms.common.kernel.DomainEvent;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** ADR-0026: đặt trước theo tác phẩm, cùng ngôn ngữ; ấn bản đổi tác phẩm (tách/gộp). */
class WorkReservationTest {

    static final UUID TENANT = UUID.randomUUID();

    final ReservationUseCasesTest.FakeRepo repo = new ReservationUseCasesTest.FakeRepo();
    final List<DomainEvent> published = new ArrayList<>();
    final Map<UUID, TitleCatalogPort.TitleInfo> catalog = new HashMap<>();
    Instant now = Instant.parse("2026-10-01T08:00:00Z");

    final UUID work = UUID.randomUUID();
    final UUID editionVi1 = UUID.randomUUID();
    final UUID editionVi2 = UUID.randomUUID();
    final UUID editionEn = UUID.randomUUID();
    final UUID readerA = UUID.randomUUID();
    final UUID readerB = UUID.randomUUID();

    ReserveTitleUseCase reserve;
    HoldCopyForNextReservationUseCase hold;
    MoveReservationsToWorkUseCase move;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        Clock clock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return now; }
        };
        // Hết sạch bản sao cùng ngôn ngữ ở mọi ấn bản của tác phẩm
        catalog.put(editionVi1, new TitleCatalogPort.TitleInfo(editionVi1, "HP1 bìa mềm", 0, work, "vi", 0L));
        catalog.put(editionVi2, new TitleCatalogPort.TitleInfo(editionVi2, "HP1 bìa cứng", 0, work, "vi", 0L));
        catalog.put(editionEn, new TitleCatalogPort.TitleInfo(editionEn, "HP1 EN", 0, work, "en", 0L));
        TitleCatalogPort port = id -> Optional.ofNullable(catalog.get(id));
        reserve = new ReserveTitleUseCase(id -> new ReaderEligibilityPort.ReaderEligibility(true, true), port, repo,
                published::add, clock);
        hold = new HoldCopyForNextReservationUseCase(repo, published::add, clock);
        move = new MoveReservationsToWorkUseCase(repo, hold, published::add, clock);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void available(UUID edition, long own, Long workSameLang) {
        TitleCatalogPort.TitleInfo t = catalog.get(edition);
        catalog.put(edition, new TitleCatalogPort.TitleInfo(edition, t.name(), own, t.workId(), t.language(), workSameLang));
    }

    private UUID targetEdition(UUID target) {
        UUID edition = UUID.randomUUID();
        catalog.put(edition, new TitleCatalogPort.TitleInfo(edition, "Bản đích", 0, target, "vi", 0L));
        return edition;
    }

    @Test
    void chiDatKhiHetBanSaoCuaCaTacPhamCungNgonNgu() {
        // ấn bản này hết, nhưng ấn bản khác cùng tác phẩm cùng ngôn ngữ còn 2 bản: workAvailableCopies = 2
        available(editionVi1, 0, 2L);
        assertThrows(CopyStillAvailableException.class, () -> reserve.execute(readerA, editionVi1));
        // hết cả tác phẩm cùng ngôn ngữ (bản tiếng Anh còn sẵn không tính)
        available(editionVi1, 0, 0L);
        ReservationResponse r = reserve.execute(readerA, editionVi1);
        assertEquals(work, r.workId());
        assertEquals("vi", r.language());
    }

    @Test
    void motDocGiaMotDatTruocConHieuLucTrenMotTacPham() {
        reserve.execute(readerA, editionVi1);
        assertThrows(AlreadyReservedException.class, () -> reserve.execute(readerA, editionVi2));
        assertThrows(AlreadyReservedException.class, () -> reserve.execute(readerA, editionEn));
    }

    @Test
    void banSaoCuaAnBanKhacCungNgonNguDuocGiuChoNguoiDauHang() {
        ReservationResponse first = reserve.execute(readerA, editionVi1);
        now = now.plusSeconds(5);
        reserve.execute(readerB, editionEn);
        UUID copy = UUID.randomUUID();

        // bản sao bìa cứng tiếng Việt (ấn bản editionVi2) sẵn sàng
        assertTrue(hold.holdIfAnyoneWaiting(work, "vi", editionVi2, copy));

        Reservation r = repo.findById(first.id()).orElseThrow();
        assertEquals(ReservationStatus.READY, r.status());
        assertEquals(copy, r.copyId());
    }

    @Test
    void banSaoTiengAnhKhongGiuChoDocGiaDatTiengViet() {
        reserve.execute(readerA, editionVi1);
        assertFalse(hold.holdIfAnyoneWaiting(work, "en", editionEn, UUID.randomUUID()));
        assertEquals(ReservationStatus.WAITING, repo.items.get(0).status());
    }

    @Test
    void eventCuKhongCoWorkIdRoiVeHangDoiTheoAnBan() {
        ReservationResponse viaEdition = reserve.execute(readerA, editionVi1);
        // không workId: chỉ khớp đúng ấn bản
        assertFalse(hold.holdIfAnyoneWaiting(editionVi2, UUID.randomUUID()));
        assertTrue(hold.holdIfAnyoneWaiting(editionVi1, UUID.randomUUID()));
        assertEquals(ReservationStatus.READY, repo.findById(viaEdition.id()).orElseThrow().status());
    }

    @Test
    void catalogChuaGanTacPhamThiDungIdTatDinhCuaAnBan() {
        UUID legacy = UUID.randomUUID();
        catalog.put(legacy, new TitleCatalogPort.TitleInfo(legacy, "Sách cũ", 0));
        ReservationResponse r = reserve.execute(readerA, legacy);
        assertEquals(LegacyWorkIds.ofTitle(legacy), r.workId());
    }

    @Test
    void congThucIdTatDinhKhopMd5CuaSql() {
        // select md5('lms-work:00000000-0000-0000-0000-000000000001')::uuid
        // md5 hex = fef373087f2ebae9746e8b26c26ed660 (kiểm bằng md5sum)
        assertEquals(UUID.fromString("fef37308-7f2e-bae9-746e-8b26c26ed660"),
                LegacyWorkIds.ofTitle(UUID.fromString("00000000-0000-0000-0000-000000000001")));
    }

    @Test
    void anBanDoiTacPhamThiDatTruocChuyenTheo() {
        ReservationResponse r = reserve.execute(readerA, editionVi1);
        UUID newWork = UUID.randomUUID();

        assertEquals(1, move.execute(editionVi1, newWork));
        assertEquals(newWork, repo.findById(r.id()).orElseThrow().workId());
        // idempotent: giao lại event không đổi gì
        assertEquals(0, move.execute(editionVi1, newWork));
    }

    @Test
    void gopTacPhamTrungDocGiaGiuDatTruocSomHonVaHuyCaConLai() {
        UUID target = UUID.randomUUID();
        UUID targetEdition = targetEdition(target);
        ReservationResponse onSource = reserve.execute(readerA, editionVi1);
        now = now.plusSeconds(10);
        ReservationResponse onTarget = reserve.execute(readerA, targetEdition);
        published.clear();

        // đặt trước ở nguồn sớm hơn nên được giữ, đặt trước ở đích bị hủy
        assertEquals(1, move.execute(editionVi1, target));

        Reservation kept = repo.findById(onSource.id()).orElseThrow();
        assertEquals(target, kept.workId());
        assertEquals(ReservationStatus.WAITING, kept.status());
        assertEquals(ReservationStatus.CANCELLED, repo.findById(onTarget.id()).orElseThrow().status());
        assertTrue(published.stream().anyMatch(e -> e instanceof ReservationCancelledEvent));
        assertEquals(1, repo.items.stream().filter(x -> x.readerId().equals(readerA) && x.isActive()).count());
    }

    @Test
    void gopTacPhamTrungDocGiaGiuCaiDangGiuBanSaoDuMuonHon() {
        UUID target = UUID.randomUUID();
        UUID targetEdition = targetEdition(target);
        // nguồn sớm hơn nhưng WAITING; đích muộn hơn nhưng READY giữ bản sao
        ReservationResponse onSource = reserve.execute(readerA, editionVi1);
        now = now.plusSeconds(10);
        ReservationResponse onTarget = reserve.execute(readerA, targetEdition);
        assertTrue(hold.holdIfAnyoneWaiting(target, "vi", targetEdition, UUID.randomUUID()));
        published.clear();

        move.execute(editionVi1, target);

        assertEquals(ReservationStatus.CANCELLED, repo.findById(onSource.id()).orElseThrow().status());
        assertEquals(ReservationStatus.READY, repo.findById(onTarget.id()).orElseThrow().status());
        // cái bị hủy không giữ bản sao nên không nhả bản sao
        assertFalse(published.stream().anyMatch(e -> e instanceof CopyReleasedEvent));
    }

    @Test
    void gopTacPhamHuyCaiDangGiuBanSaoThiNhaBanSaoVeKho() {
        UUID target = UUID.randomUUID();
        UUID targetEdition = targetEdition(target);
        // cả hai READY: giữ cái đặt sớm hơn, cái còn lại bị hủy và nhả bản sao (không ai chờ thì CopyReleasedEvent)
        ReservationResponse onTarget = reserve.execute(readerA, targetEdition);
        assertTrue(hold.holdIfAnyoneWaiting(target, "vi", targetEdition, UUID.randomUUID()));
        now = now.plusSeconds(10);
        ReservationResponse onSource = reserve.execute(readerA, editionVi1);
        assertTrue(hold.holdIfAnyoneWaiting(work, "vi", editionVi1, UUID.randomUUID()));
        published.clear();

        move.execute(editionVi1, target);

        assertEquals(ReservationStatus.READY, repo.findById(onTarget.id()).orElseThrow().status());
        assertEquals(ReservationStatus.CANCELLED, repo.findById(onSource.id()).orElseThrow().status());
        assertTrue(published.stream().anyMatch(e -> e instanceof CopyReleasedEvent));
    }

    @Test
    void consumerChuyenEventSangDungUseCase() {
        HoldCopyForNextReservationUseCase holdMock = mock(HoldCopyForNextReservationUseCase.class);
        MoveReservationsToWorkUseCase moveMock = mock(MoveReservationsToWorkUseCase.class);
        InventoryEventConsumer consumer = new InventoryEventConsumer(new ObjectMapper(), holdMock, moveMock);
        UUID edition = UUID.randomUUID();
        UUID newWork = UUID.randomUUID();
        UUID copy = UUID.randomUUID();

        consumer.onEvent("{\"eventType\":\"EditionWorkChangedEvent\",\"tenantId\":\"" + TENANT + "\",\"payload\":{\"editionId\":\""
                + edition + "\",\"oldWorkId\":\"" + UUID.randomUUID() + "\",\"newWorkId\":\"" + newWork + "\"}}");
        verify(moveMock).execute(edition, newWork);

        consumer.onEvent("{\"eventType\":\"CopyAvailableEvent\",\"tenantId\":\"" + TENANT + "\",\"payload\":{\"copyId\":\"" + copy
                + "\",\"titleId\":\"" + edition + "\",\"workId\":\"" + newWork + "\",\"language\":\"vi\"}}");
        verify(holdMock).holdIfAnyoneWaiting(newWork, "vi", edition, copy);
    }
}
