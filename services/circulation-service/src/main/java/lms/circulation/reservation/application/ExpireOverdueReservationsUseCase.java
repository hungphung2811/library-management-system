package lms.circulation.reservation.application;

import lms.circulation.reservation.domain.Reservation;
import lms.circulation.reservation.domain.ReservationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC14: xử lý đặt trước quá hạn giữ. Chuyển EXPIRED rồi giải phóng bản sao: giữ cho người kế tiếp (QĐ6) hoặc trả
 * về kho. Chạy lại an toàn vì chỉ chọn các đặt trước READY đã quá hạn.
 */
public class ExpireOverdueReservationsUseCase {

    private final ReservationRepository repository;
    private final HoldCopyForNextReservationUseCase holdCopy;
    private final PublishReservationEventPort eventPort;
    private final Clock clock;

    public ExpireOverdueReservationsUseCase(ReservationRepository repository, HoldCopyForNextReservationUseCase holdCopy,
                                            PublishReservationEventPort eventPort, Clock clock) {
        this.repository = repository;
        this.holdCopy = holdCopy;
        this.eventPort = eventPort;
        this.clock = clock;
    }

    /** @return số đặt trước đã chuyển sang EXPIRED. */
    @Transactional
    public int execute() {
        LocalDate today = LocalDate.now(clock);
        int processed = 0;
        for (Reservation reservation : repository.findHoldExpired(today)) {
            reservation.expire(today, Instant.now(clock));
            repository.save(reservation);
            reservation.pullEvents().forEach(eventPort::publish);
            holdCopy.holdOrRelease(reservation.titleId(), reservation.copyId());
            processed++;
        }
        return processed;
    }
}
