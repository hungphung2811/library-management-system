package lms.circulation.borrowing.application;

import lms.circulation.borrowing.domain.BorrowingRecord;

public interface SaveBorrowingPort {
    BorrowingRecord save(BorrowingRecord record);
}
