package lms.circulation.borrowing.infrastructure.integration;

import lms.circulation.borrowing.application.CopyReservationPort;
import lms.circulation.reservation.application.FulfillReservationUseCase;
import java.util.Optional;
import java.util.UUID;

/** Nối borrowing với tầng application của reservation (cùng service, cùng transaction; không qua event). */
public class ReservationHoldAdapter implements CopyReservationPort {

    private final FulfillReservationUseCase reservations;

    public ReservationHoldAdapter(FulfillReservationUseCase reservations) {
        this.reservations = reservations;
    }

    @Override
    public Optional<UUID> holderOf(UUID copyId) {
        return reservations.holderOf(copyId);
    }

    @Override
    public void fulfill(UUID copyId, UUID readerId) {
        reservations.execute(copyId, readerId);
    }
}
