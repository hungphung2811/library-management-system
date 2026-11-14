package lms.circulation.borrowing.domain;

import lms.circulation.borrowing.domain.BorrowingId;
import lms.circulation.borrowing.domain.BorrowingRecord;
import java.util.Optional;
import java.util.UUID;

public interface BorrowingRepository {
    Optional<BorrowingRecord> findById(BorrowingId id);

    /** Số bản sao người dùng đang mượn và chưa trả. */
    long countActiveItemsByUserId(UUID userId);
}
