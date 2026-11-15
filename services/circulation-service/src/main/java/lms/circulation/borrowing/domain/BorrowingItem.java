package lms.circulation.borrowing.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Một bản sao sách vật lý (BookCopy, thuộc catalog) trong phiếu mượn. Đang mượn thì returnDate và condition cùng null;
 * đóng lượt mượn (trả hoặc báo mất) thì cả hai cùng có giá trị GOOD, DAMAGED, LOST (khớp ràng buộc
 * ck_borrowing_items_closed của migration V4).
 */
public record BorrowingItem(UUID bookCopyId, LocalDate returnDate, ItemCondition condition) {

    public BorrowingItem {
        if (bookCopyId == null) throw new IllegalArgumentException("bookCopyId không được null");
        if ((returnDate == null) != (condition == null)) {
            throw new IllegalArgumentException("Ngày đóng và tình trạng bản sao phải cùng có hoặc cùng không: " + bookCopyId);
        }
    }

    public static BorrowingItem borrowed(UUID bookCopyId) {
        return new BorrowingItem(bookCopyId, null, null);
    }

    /** Đã đóng lượt mượn (trả hoặc báo mất). */
    public boolean isReturned() {
        return returnDate != null;
    }

    /** Trả nguyên vẹn (GOOD). */
    public BorrowingItem returnedOn(LocalDate date) {
        return closedOn(date, ItemCondition.GOOD);
    }

    /** Đóng lượt mượn với ngày đóng và tình trạng bản sao (UC07 trả nguyên vẹn/hỏng, UC08 mất). */
    public BorrowingItem closedOn(LocalDate date, ItemCondition condition) {
        if (date == null || condition == null) {
            throw new IllegalArgumentException("Đóng lượt mượn cần ngày đóng và tình trạng bản sao");
        }
        return new BorrowingItem(bookCopyId, date, condition);
    }
}
