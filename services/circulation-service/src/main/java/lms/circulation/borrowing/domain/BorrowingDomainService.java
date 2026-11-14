package lms.circulation.borrowing.domain;

import lms.circulation.borrowing.domain.BorrowingLimitExceededException;
import lms.circulation.borrowing.domain.BorrowingRecord;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Domain Service: quy tắc giới hạn mượn theo người dùng, vượt phạm vi một aggregate. */
public class BorrowingDomainService {

    public static final int MAX_ACTIVE_ITEMS_PER_USER = 10;

    public BorrowingRecord borrow(UUID userId, List<UUID> bookCopyIds, long currentActiveItems, LocalDate today) {
        int requested = bookCopyIds == null ? 0 : bookCopyIds.size();
        if (currentActiveItems + requested > MAX_ACTIVE_ITEMS_PER_USER) {
            throw new BorrowingLimitExceededException(MAX_ACTIVE_ITEMS_PER_USER);
        }
        return BorrowingRecord.borrow(userId, bookCopyIds, today);
    }
}
