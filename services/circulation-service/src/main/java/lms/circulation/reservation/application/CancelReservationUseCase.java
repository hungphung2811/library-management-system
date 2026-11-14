package lms.circulation.reservation.application;

import lms.circulation.reservation.domain.Reservation;
import lms.circulation.reservation.domain.ReservationNotFoundException;
import lms.circulation.reservation.domain.ReservationRepository;
import lms.circulation.reservation.domain.ReservationStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC04 - Hủy đặt trước. Đặt trước của người khác được coi như không tồn tại để không lộ dữ liệu.
 * Hủy đặt trước READY thì bản sao đang giữ được chuyển cho người kế tiếp hoặc trả về kho.
 */
public class CancelReservationUseCase {

    private final ReservationRepository repository;
    private final HoldCopyForNextReservationUseCase holdCopy;
    private final PublishReservationEventPort eventPort;
    private final Clock clock;

    public CancelReservationUseCase(ReservationRepository repository, HoldCopyForNextReservationUseCase holdCopy,
                                    PublishReservationEventPort eventPort, Clock clock) {
        this.repository = repository;
        this.holdCopy = holdCopy;
        this.eventPort = eventPort;
        this.clock = clock;
    }

    @Transactional
    public ReservationResponse execute(UUID readerId, UUID reservationId) {
        Reservation reservation = repository.findById(reservationId)
                .filter(r -> r.isOwnedBy(readerId))
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        boolean wasHoldingCopy = reservation.status() == ReservationStatus.READY;
        reservation.cancel(Instant.now(clock));
        Reservation saved = repository.save(reservation);
        reservation.pullEvents().forEach(eventPort::publish);
        if (wasHoldingCopy) {
            holdCopy.holdOrRelease(reservation.titleId(), reservation.copyId());
        }
        return ReservationResponse.from(saved, 0);
    }
}
