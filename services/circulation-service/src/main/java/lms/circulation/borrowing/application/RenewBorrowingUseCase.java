package lms.circulation.borrowing.application;

import lms.circulation.borrowing.application.BorrowingResponse;
import lms.circulation.borrowing.application.BorrowingMapper;
import lms.circulation.borrowing.application.SaveBorrowingPort;
import lms.circulation.borrowing.domain.BorrowingNotFoundException;
import lms.circulation.borrowing.domain.BorrowingId;
import lms.circulation.borrowing.domain.BorrowingRecord;
import lms.circulation.borrowing.domain.BorrowingRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** TODO: kiểm tra yêu cầu đặt trước (Reservation Service) trước khi cho gia hạn. */
public class RenewBorrowingUseCase {

    private final BorrowingRepository repository;
    private final SaveBorrowingPort savePort;
    private final Clock clock;

    public RenewBorrowingUseCase(BorrowingRepository repository, SaveBorrowingPort savePort, Clock clock) {
        this.repository = repository;
        this.savePort = savePort;
        this.clock = clock;
    }

    @Transactional
    public BorrowingResponse execute(UUID borrowingId) {
        BorrowingRecord record = repository.findById(new BorrowingId(borrowingId))
                .orElseThrow(() -> new BorrowingNotFoundException(borrowingId));
        record.renew(LocalDate.now(clock));
        return BorrowingMapper.toResponse(savePort.save(record));
    }
}
