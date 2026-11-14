package lms.circulation.reservation.application;

import lms.circulation.reservation.domain.CopyReleasedEvent;
import lms.circulation.reservation.domain.Reservation;
import lms.circulation.reservation.domain.ReservationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * QĐ6, QĐ9: một bản sao vừa sẵn sàng (nhập kho, sửa xong, trả về, hoặc nhả từ đặt trước hết hạn/bị hủy) thì
 * được giữ ngay cho người đầu hàng đợi trong 3 ngày. Idempotent theo copyId nên event giao lại không sao.
 */
public class HoldCopyForNextReservationUseCase {

    private final ReservationRepository repository;
    private final PublishReservationEventPort eventPort;
    private final Clock clock;

    public HoldCopyForNextReservationUseCase(ReservationRepository repository, PublishReservationEventPort eventPort,
                                             Clock clock) {
        this.repository = repository;
        this.eventPort = eventPort;
        this.clock = clock;
    }

    /** Dùng cho event "bản sao sẵn sàng": giữ cho người chờ nếu có; không có thì không làm gì. @return true nếu đã giữ. */
    @Transactional
    public boolean holdIfAnyoneWaiting(UUID titleId, UUID copyId) {
        if (repository.existsReadyForCopy(copyId)) {
            return true;
        }
        Optional<Reservation> next = repository.findNextWaitingForUpdate(titleId);
        if (next.isEmpty()) {
            return false;
        }
        Reservation reservation = next.get();
        reservation.markReady(copyId, LocalDate.now(clock), Instant.now(clock));
        repository.save(reservation);
        reservation.pullEvents().forEach(eventPort::publish);
        return true;
    }

    /** Dùng khi bản sao đang giữ bị nhả (hết hạn/hủy): giữ cho người kế tiếp, không có ai thì báo catalog trả về kho. */
    @Transactional
    public void holdOrRelease(UUID titleId, UUID copyId) {
        if (!holdIfAnyoneWaiting(titleId, copyId)) {
            eventPort.publish(new CopyReleasedEvent(UUID.randomUUID(), copyId, titleId, Instant.now(clock)));
        }
    }
}
