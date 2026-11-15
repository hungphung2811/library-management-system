package lms.circulation.reservation.application;

import lms.circulation.reservation.domain.Reservation;
import lms.circulation.reservation.domain.ReservationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * API của context reservation cho borrowing (UC06, QĐ4c): ai đang được giữ bản sao, và chuyển đặt trước
 * sang FULFILLED khi đúng người đó nhận bản sao. Chạy chung transaction với lập phiếu mượn.
 */
public class FulfillReservationUseCase {

    private final ReservationRepository repository;
    private final PublishReservationEventPort eventPort;
    private final Clock clock;

    public FulfillReservationUseCase(ReservationRepository repository, PublishReservationEventPort eventPort, Clock clock) {
        this.repository = repository;
        this.eventPort = eventPort;
        this.clock = clock;
    }

    /** Độc giả đang được giữ bản sao (đặt trước READY), nếu có. */
    @Transactional(readOnly = true)
    public Optional<UUID> holderOf(UUID copyId) {
        return repository.findReadyByCopyId(copyId).map(Reservation::readerId);
    }

    /** Chuyển đặt trước READY giữ bản sao này sang FULFILLED nếu thuộc về độc giả. @return true nếu có chuyển. */
    @Transactional
    public boolean execute(UUID copyId, UUID readerId) {
        Optional<Reservation> held = repository.findReadyByCopyId(copyId).filter(r -> r.isOwnedBy(readerId));
        if (held.isEmpty()) {
            return false;
        }
        Reservation reservation = held.get();
        reservation.fulfill(Instant.now(clock));
        repository.save(reservation);
        reservation.pullEvents().forEach(eventPort::publish);
        return true;
    }
}
