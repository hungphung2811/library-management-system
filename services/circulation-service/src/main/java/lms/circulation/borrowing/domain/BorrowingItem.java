package lms.circulation.borrowing.domain;

import java.time.LocalDate;
import java.util.UUID;

/** Một bản sao sách vật lý (BookCopy, thuộc Inventory Service) trong phiếu mượn. */
public record BorrowingItem(UUID bookCopyId, LocalDate returnDate) {

    public BorrowingItem {
        if (bookCopyId == null) throw new IllegalArgumentException("bookCopyId không được null");
    }

    public static BorrowingItem borrowed(UUID bookCopyId) {
        return new BorrowingItem(bookCopyId, null);
    }

    public boolean isReturned() {
        return returnDate != null;
    }

    public BorrowingItem returnedOn(LocalDate date) {
        return new BorrowingItem(bookCopyId, date);
    }
}
