package lms.circulation.borrowing.application;

import lms.circulation.borrowing.application.BorrowingResponse;
import lms.circulation.borrowing.application.BorrowingMapper;
import lms.circulation.borrowing.application.ReturnBookCommand;
import lms.circulation.borrowing.application.PublishBorrowingEventPort;
import lms.circulation.borrowing.application.SaveBorrowingPort;
import lms.circulation.borrowing.domain.BorrowingNotFoundException;
import lms.circulation.borrowing.domain.BorrowingId;
import lms.circulation.borrowing.domain.BorrowingRecord;
import lms.circulation.borrowing.domain.BorrowingRepository;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.transaction.annotation.Transactional;

public class ReturnBookUseCase {

    private final BorrowingRepository repository;
    private final SaveBorrowingPort savePort;
    private final PublishBorrowingEventPort eventPort;
    private final Clock clock;

    public ReturnBookUseCase(BorrowingRepository repository, SaveBorrowingPort savePort,
                             PublishBorrowingEventPort eventPort, Clock clock) {
        this.repository = repository;
        this.savePort = savePort;
        this.eventPort = eventPort;
        this.clock = clock;
    }

    @Transactional
    public BorrowingResponse execute(ReturnBookCommand command) {
        BorrowingRecord record = repository.findById(new BorrowingId(command.borrowingId()))
                .orElseThrow(() -> new BorrowingNotFoundException(command.borrowingId()));
        record.returnCopy(command.bookCopyId(), LocalDate.now(clock));
        BorrowingRecord saved = savePort.save(record);
        record.pullEvents().forEach(eventPort::publish);
        return BorrowingMapper.toResponse(saved);
    }
}
