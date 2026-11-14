package lms.circulation.borrowing.application;

import lms.circulation.borrowing.application.BorrowingResponse;
import lms.circulation.borrowing.application.BorrowingMapper;
import lms.circulation.borrowing.application.BorrowBookCommand;
import lms.circulation.borrowing.application.PublishBorrowingEventPort;
import lms.circulation.borrowing.application.SaveBorrowingPort;
import lms.circulation.borrowing.domain.BorrowingRecord;
import lms.circulation.borrowing.domain.BorrowingRepository;
import lms.circulation.borrowing.domain.BorrowingDomainService;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lưu ý production: nên dùng Transactional Outbox thay cho publish trực tiếp
 * để dữ liệu và event không lệch nhau khi lỗi giữa chừng.
 */
public class BorrowBookUseCase {

    private final BorrowingDomainService domainService;
    private final BorrowingRepository repository;
    private final SaveBorrowingPort savePort;
    private final PublishBorrowingEventPort eventPort;
    private final Clock clock;

    public BorrowBookUseCase(BorrowingDomainService domainService, BorrowingRepository repository,
                             SaveBorrowingPort savePort, PublishBorrowingEventPort eventPort, Clock clock) {
        this.domainService = domainService;
        this.repository = repository;
        this.savePort = savePort;
        this.eventPort = eventPort;
        this.clock = clock;
    }

    @Transactional
    public BorrowingResponse execute(BorrowBookCommand command) {
        long active = repository.countActiveItemsByUserId(command.userId());
        BorrowingRecord record = domainService.borrow(command.userId(), command.bookCopyIds(), active, LocalDate.now(clock));
        BorrowingRecord saved = savePort.save(record);
        record.pullEvents().forEach(eventPort::publish);
        return BorrowingMapper.toResponse(saved);
    }
}
