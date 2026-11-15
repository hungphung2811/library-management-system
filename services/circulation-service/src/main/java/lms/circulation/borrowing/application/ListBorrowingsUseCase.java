package lms.circulation.borrowing.application;

import lms.circulation.borrowing.domain.BorrowingRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** UC05: phiếu mượn của một độc giả (sách đang mượn, hạn trả), mới nhất trước. */
public class ListBorrowingsUseCase {

    private final BorrowingRepository repository;

    public ListBorrowingsUseCase(BorrowingRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<BorrowingResponse> execute(UUID readerId) {
        return repository.findByUserId(readerId).stream().map(BorrowingMapper::toResponse).toList();
    }
}
