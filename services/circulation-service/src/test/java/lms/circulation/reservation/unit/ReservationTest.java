package lms.circulation.reservation.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import lms.circulation.reservation.domain.InvalidReservationException;
import lms.circulation.reservation.domain.Reservation;
import lms.circulation.reservation.domain.ReservationCancelledEvent;
import lms.circulation.reservation.domain.ReservationCreatedEvent;
import lms.circulation.reservation.domain.ReservationStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReservationTest {

    static final Instant NOW = Instant.parse("2026-10-01T08:00:00Z");

    @Test
    void taoMoiLaWaitingVaPhatSinhEvent() {
        Reservation r = Reservation.create(UUID.randomUUID(), UUID.randomUUID(), NOW);
        assertEquals(ReservationStatus.WAITING, r.status());
        assertTrue(r.isActive());
        var events = r.pullEvents();
        assertEquals(1, events.size());
        assertInstanceOf(ReservationCreatedEvent.class, events.get(0));
        assertTrue(r.pullEvents().isEmpty());
    }

    @Test
    void huyChuyenSangCancelledVaPhatSinhEvent() {
        Reservation r = Reservation.create(UUID.randomUUID(), UUID.randomUUID(), NOW);
        r.pullEvents();
        r.cancel(NOW.plusSeconds(60));
        assertEquals(ReservationStatus.CANCELLED, r.status());
        assertFalse(r.isActive());
        assertInstanceOf(ReservationCancelledEvent.class, r.pullEvents().get(0));
    }

    @Test
    void khongHuyDuocDatTruocKhongConHieuLuc() {
        for (ReservationStatus s : new ReservationStatus[]{ReservationStatus.CANCELLED, ReservationStatus.EXPIRED,
                ReservationStatus.FULFILLED}) {
            Reservation r = Reservation.restore(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), NOW, s);
            assertThrows(InvalidReservationException.class, () -> r.cancel(NOW));
        }
    }

    @Test
    void readyVanHuyDuocVaChiChuSoHuuLaChuDatTruoc() {
        UUID reader = UUID.randomUUID();
        Reservation r = Reservation.restore(UUID.randomUUID(), reader, UUID.randomUUID(), NOW, ReservationStatus.READY,
                UUID.randomUUID(), java.time.LocalDate.of(2026, 10, 4));
        assertTrue(r.isOwnedBy(reader));
        assertFalse(r.isOwnedBy(UUID.randomUUID()));
        r.cancel(NOW);
        assertEquals(ReservationStatus.CANCELLED, r.status());
    }

    @Test
    void giuBanSaoChiChoWaitingVaHanGiuLa3Ngay() {
        Reservation r = Reservation.create(UUID.randomUUID(), UUID.randomUUID(), NOW);
        r.pullEvents();
        UUID copy = UUID.randomUUID();
        r.markReady(copy, java.time.LocalDate.of(2026, 10, 1), NOW);
        assertEquals(ReservationStatus.READY, r.status());
        assertEquals(java.time.LocalDate.of(2026, 10, 4), r.holdUntil());
        assertEquals(copy, r.copyId());
        assertInstanceOf(lms.circulation.reservation.domain.ReservationReadyEvent.class, r.pullEvents().get(0));
        assertThrows(InvalidReservationException.class, () -> r.markReady(UUID.randomUUID(), java.time.LocalDate.of(2026, 10, 1), NOW));
    }

    @Test
    void chiHetHanSauNgayCuoiCuaHanGiu() {
        Reservation r = Reservation.create(UUID.randomUUID(), UUID.randomUUID(), NOW);
        r.markReady(UUID.randomUUID(), java.time.LocalDate.of(2026, 10, 1), NOW);
        assertFalse(r.isHoldExpired(java.time.LocalDate.of(2026, 10, 4)));
        assertTrue(r.isHoldExpired(java.time.LocalDate.of(2026, 10, 5)));
        assertThrows(InvalidReservationException.class, () -> r.expire(java.time.LocalDate.of(2026, 10, 4), NOW));
        r.expire(java.time.LocalDate.of(2026, 10, 5), NOW);
        assertEquals(ReservationStatus.EXPIRED, r.status());
        assertFalse(r.isActive());
    }
}
