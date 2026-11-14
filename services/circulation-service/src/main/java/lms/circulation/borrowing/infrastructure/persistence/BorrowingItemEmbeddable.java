package lms.circulation.borrowing.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;
import java.util.UUID;

@Embeddable
class BorrowingItemEmbeddable {

    @Column(name = "book_copy_id", nullable = false)
    private UUID bookCopyId;

    @Column(name = "return_date")
    private LocalDate returnDate;

    protected BorrowingItemEmbeddable() {
    }

    BorrowingItemEmbeddable(UUID bookCopyId, LocalDate returnDate) {
        this.bookCopyId = bookCopyId;
        this.returnDate = returnDate;
    }

    UUID getBookCopyId() { return bookCopyId; }
    LocalDate getReturnDate() { return returnDate; }
}
