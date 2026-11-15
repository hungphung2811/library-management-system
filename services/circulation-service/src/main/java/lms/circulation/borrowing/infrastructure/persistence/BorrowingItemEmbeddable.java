package lms.circulation.borrowing.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.LocalDate;
import java.util.UUID;
import lms.circulation.borrowing.domain.ItemCondition;

@Embeddable
class BorrowingItemEmbeddable {

    @Column(name = "book_copy_id", nullable = false)
    private UUID bookCopyId;

    @Column(name = "return_date")
    private LocalDate returnDate;

    /** NULL khi đang mượn; ràng buộc ck_borrowing_items_closed (V4) đòi cùng null/cùng có với return_date. */
    @Enumerated(EnumType.STRING)
    @Column(name = "item_condition", length = 10)
    private ItemCondition condition;

    protected BorrowingItemEmbeddable() {
    }

    BorrowingItemEmbeddable(UUID bookCopyId, LocalDate returnDate, ItemCondition condition) {
        this.bookCopyId = bookCopyId;
        this.returnDate = returnDate;
        this.condition = condition;
    }

    UUID getBookCopyId() { return bookCopyId; }
    LocalDate getReturnDate() { return returnDate; }
    ItemCondition getCondition() { return condition; }
}
