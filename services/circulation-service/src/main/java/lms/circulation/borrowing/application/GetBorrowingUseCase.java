package lms.circulation.borrowing.application;

import lms.circulation.borrowing.domain.BorrowingNotFoundException;
import lms.circulation.borrowing.domain.BorrowingId;
import lms.circulation.borrowing.domain.BorrowingRepository;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Xem một phiếu mượn: của chính người gọi (điều kiện sở hữu nằm trong truy vấn) hoặc bất kỳ phiếu trong tenant (nhân viên có khóa riêng). */
public class GetBorrowingUseCase {

    private final BorrowingRepository repository;

    public GetBorrowingUseCase(BorrowingRepository repository) {
        this.repository = repository;
    }

    /** Phiếu của requesterId; không có hoặc của người khác đều là BorrowingNotFoundException (404), không lộ tồn tại. */
    @Transactional(readOnly = true)
    public BorrowingResponse executeOwn(UUID borrowingId, UUID requesterId) {
        if (requesterId == null) {
            throw new BorrowingNotFoundException(borrowingId);
        }
        return repository.findByIdAndUserId(new BorrowingId(borrowingId), requesterId)
                .map(BorrowingMapper::toResponse)
                .orElseThrow(() -> new BorrowingNotFoundException(borrowingId));
    }

    /** Phiếu bất kỳ trong tenant (quyền do khóa endpoint kiểm). */
    @Transactional(readOnly = true)
    public BorrowingResponse executeAny(UUID borrowingId) {
        return repository.findById(new BorrowingId(borrowingId))
                .map(BorrowingMapper::toResponse)
                .orElseThrow(() -> new BorrowingNotFoundException(borrowingId));
    }
}
