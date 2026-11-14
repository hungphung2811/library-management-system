package lms.circulation.reservation.application;

import lms.circulation.reservation.domain.AlreadyReservedException;
import lms.circulation.reservation.domain.CopyStillAvailableException;
import lms.circulation.reservation.domain.ReaderNotEligibleException;
import lms.circulation.reservation.domain.Reservation;
import lms.circulation.reservation.domain.ReservationRepository;
import lms.circulation.reservation.domain.TitleNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * UC03 - Đặt trước sách. QĐ2 (thẻ hợp lệ), QĐ5 (chỉ khi hết bản sao, mỗi độc giả một đặt trước còn hiệu lực),
 * QĐ7 (xếp cuối hàng đợi FIFO). Không bọc transaction quanh các lời gọi HTTP; tính duy nhất được đảm bảo bằng
 * unique index nên kiểm tra trước chỉ để trả lỗi sớm. Production nên dùng Transactional Outbox cho event.
 */
public class ReserveTitleUseCase {

    private final ReaderEligibilityPort eligibility;
    private final TitleCatalogPort catalog;
    private final ReservationRepository repository;
    private final PublishReservationEventPort eventPort;
    private final Clock clock;

    public ReserveTitleUseCase(ReaderEligibilityPort eligibility, TitleCatalogPort catalog,
                               ReservationRepository repository, PublishReservationEventPort eventPort, Clock clock) {
        this.eligibility = eligibility;
        this.catalog = catalog;
        this.repository = repository;
        this.eventPort = eventPort;
        this.clock = clock;
    }

    public ReservationResponse execute(UUID readerId, UUID titleId) {
        ReaderEligibilityPort.ReaderEligibility reader = eligibility.check(readerId);
        if (!reader.hasCard()) {
            throw new ReaderNotEligibleException("Người dùng chưa có thẻ độc giả");
        }
        if (!reader.cardValid()) {
            throw new ReaderNotEligibleException("Thẻ hết hạn hoặc tài khoản bị khóa");
        }

        TitleCatalogPort.TitleInfo title = catalog.findTitle(titleId).orElseThrow(() -> new TitleNotFoundException(titleId));
        if (title.availableCopies() > 0) {
            throw new CopyStillAvailableException();
        }
        if (repository.existsActive(readerId, titleId)) {
            throw new AlreadyReservedException();
        }

        Reservation reservation = Reservation.create(readerId, titleId, Instant.now(clock));
        Reservation saved = repository.save(reservation);
        reservation.pullEvents().forEach(eventPort::publish);
        return ReservationResponse.from(saved, repository.queuePosition(saved));
    }
}
