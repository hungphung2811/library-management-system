package lms.circulation.borrowing.application;

import lms.circulation.borrowing.application.BorrowingResponse;
import lms.circulation.borrowing.application.BorrowingMapper;
import lms.circulation.borrowing.domain.BorrowingNotFoundException;
import lms.circulation.borrowing.domain.BorrowingId;
import lms.circulation.borrowing.domain.BorrowingRepository;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class GetBorrowingUseCase {

    private final BorrowingRepository repository;

    public GetBorrowingUseCase(BorrowingRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public BorrowingResponse execute(UUID borrowingId) {
        return repository.findById(new BorrowingId(borrowingId))
                .map(BorrowingMapper::toResponse)
                .orElseThrow(() -> new BorrowingNotFoundException(borrowingId));
    }
}
