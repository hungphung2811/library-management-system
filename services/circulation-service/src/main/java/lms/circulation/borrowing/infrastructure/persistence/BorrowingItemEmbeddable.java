package lms.circulation.borrowing.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.LocalDate;
import java.util.UUID;
import lms.circulation.borrowing.domain.BorrowCondition;
import lms.circulation.borrowing.domain.ConditionGrade;
import lms.circulation.borrowing.domain.ItemCondition;

@Embeddable
class BorrowingItemEmbeddable {

    @Column(name = "book_copy_id", nullable = false)
    private UUID bookCopyId;

    /** Snapshot đầu sách lúc mượn (migration V9); NULL ở dòng cũ. */
    @Column(name = "title_id")
    private UUID titleId;

    @Column(name = "return_date")
    private LocalDate returnDate;

    /** NULL khi đang mượn; ràng buộc ck_borrowing_items_closed (V4) đòi cùng null/cùng có với return_date. */
    @Enumerated(EnumType.STRING)
    @Column(name = "item_condition", length = 10)
    private ItemCondition condition;

    /** Tình trạng bản sao lúc mượn (migration V11): bất biến, chỉ ghi khi lập phiếu. */
    @Enumerated(EnumType.STRING)
    @Column(name = "condition_grade", length = 10)
    private ConditionGrade conditionGrade;

    @Column(name = "condition_note", length = 500)
    private String conditionNote;

    /** FALSE ở lượt mượn lập trước V11 ("không có dữ liệu lúc mượn"). */
    @Column(name = "condition_recorded", nullable = false)
    private boolean conditionRecorded;

    protected BorrowingItemEmbeddable() {
    }

    BorrowingItemEmbeddable(UUID bookCopyId, UUID titleId, LocalDate returnDate, ItemCondition condition,
                            BorrowCondition borrowCondition) {
        this.bookCopyId = bookCopyId;
        this.titleId = titleId;
        this.returnDate = returnDate;
        this.condition = condition;
        if (borrowCondition != null) {
            this.conditionRecorded = true;
            this.conditionGrade = borrowCondition.grade();
            this.conditionNote = borrowCondition.note();
        }
    }

    BorrowCondition borrowCondition() {
        return conditionRecorded ? new BorrowCondition(conditionGrade, conditionNote) : null;
    }

    UUID getBookCopyId() { return bookCopyId; }
    UUID getTitleId() { return titleId; }
    LocalDate getReturnDate() { return returnDate; }
    ItemCondition getCondition() { return condition; }
}
