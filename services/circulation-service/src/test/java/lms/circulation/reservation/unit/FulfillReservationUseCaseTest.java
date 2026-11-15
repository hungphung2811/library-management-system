package lms.circulation.reservation.unit;

import static org.assertj.core.api.Assertions.assertThat;

import lms.circulation.reservation.application.FulfillReservationUseCase;
import lms.circulation.reservation.domain.Reservation;
import lms.circulation.reservation.domain.ReservationFulfilledEvent;
import lms.circulation.reservation.domain.ReservationStatus;
import lms.common.kernel.DomainEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FulfillReservationUseCaseTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"), ZoneOffset.UTC);
    private final ReservationUseCasesTest.FakeRepo repo = new ReservationUseCasesTest.FakeRepo();
    private final List<DomainEvent> events = new ArrayList<>();
    private final FulfillReservationUseCase useCase = new FulfillReservationUseCase(repo, events::add, clock);

    private Reservation readyFor(UUID reader, UUID copy) {
        Reservation r = Reservation.create(reader, UUID.randomUUID(), Instant.now(clock));
        r.markReady(copy, LocalDate.of(2026, 3, 1), Instant.now(clock));
        r.pullEvents();
        return repo.save(r);
    }

    @Test
    void biet_ai_dang_duoc_giu_ban_sao() {
        UUID reader = UUID.randomUUID();
        UUID copy = UUID.randomUUID();
        readyFor(reader, copy);

        assertThat(useCase.holderOf(copy)).contains(reader);
        assertThat(useCase.holderOf(UUID.randomUUID())).isEmpty();
    }

    @Test
    void dung_nguoi_nhan_thi_dat_truoc_thanh_fulfilled_va_phat_event() {
        UUID reader = UUID.randomUUID();
        UUID copy = UUID.randomUUID();
        Reservation r = readyFor(reader, copy);

        assertThat(useCase.execute(copy, reader)).isTrue();

        assertThat(repo.findById(r.id()).orElseThrow().status()).isEqualTo(ReservationStatus.FULFILLED);
        assertThat(events).hasSize(1).first().isInstanceOf(ReservationFulfilledEvent.class);
        assertThat(useCase.holderOf(copy)).isEmpty();
    }

    @Test
    void nguoi_khac_khong_nhan_duoc_dat_truoc_cua_nguoi_giu() {
        UUID reader = UUID.randomUUID();
        UUID copy = UUID.randomUUID();
        Reservation r = readyFor(reader, copy);

        assertThat(useCase.execute(copy, UUID.randomUUID())).isFalse();

        assertThat(repo.findById(r.id()).orElseThrow().status()).isEqualTo(ReservationStatus.READY);
        assertThat(events).isEmpty();
    }
}
