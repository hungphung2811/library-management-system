package lms.circulation.borrowing.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Một bản sao sách vật lý (BookCopy, thuộc catalog) trong phiếu mượn. Đang mượn thì returnDate và condition cùng null;
 * đóng lượt mượn (trả hoặc báo mất) thì cả hai cùng có giá trị GOOD, DAMAGED, LOST (khớp ràng buộc
 * ck_borrowing_items_closed của migration V4). titleId là snapshot đầu sách lúc mượn (nullable, dòng cũ không có).
 * borrowCondition là tình trạng bản sao chụp lúc lập phiếu (migration V11): null = lượt mượn cũ không có dữ liệu lúc
 * mượn; khác null nhưng grade null = bản sao chưa có tình trạng ở catalog. Bất biến: mọi phép biến đổi đều giữ nguyên.
 */
public record BorrowingItem(UUID bookCopyId, UUID titleId, LocalDate returnDate, ItemCondition condition,
                            BorrowCondition borrowCondition) {

    /** Dòng không có snapshot tình trạng lúc mượn (dữ liệu cũ trước migration V11). */
    public BorrowingItem(UUID bookCopyId, UUID titleId, LocalDate returnDate, ItemCondition condition) {
        this(bookCopyId, titleId, returnDate, condition, null);
    }

    /** Dòng không có snapshot đầu sách (dữ liệu cũ trước migration V9): titleId null. */
    public BorrowingItem(UUID bookCopyId, LocalDate returnDate, ItemCondition condition) {
        this(bookCopyId, null, returnDate, condition, null);
    }

    public BorrowingItem {
        if (bookCopyId == null) throw new IllegalArgumentException("bookCopyId không được null");
        if ((returnDate == null) != (condition == null)) {
            throw new IllegalArgumentException("Ngày đóng và tình trạng bản sao phải cùng có hoặc cùng không: " + bookCopyId);
        }
    }

    public static BorrowingItem borrowed(UUID bookCopyId) {
        return new BorrowingItem(bookCopyId, null, null, null, null);
    }

    /** Snapshot đầu sách của bản sao lúc mượn (phục vụ thống kê top sách mượn, ADR-0018); titleId có thể null. */
    public BorrowingItem withTitleId(UUID titleId) {
        return new BorrowingItem(bookCopyId, titleId, returnDate, condition, borrowCondition);
    }

    /** Ghi tình trạng lúc mượn cho dòng của phiếu mới lập, trước khi lưu (không có đường nào sửa sau đó). */
    public BorrowingItem withBorrowCondition(BorrowCondition borrowCondition) {
        return new BorrowingItem(bookCopyId, titleId, returnDate, condition, borrowCondition);
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
        return new BorrowingItem(bookCopyId, titleId, date, condition, borrowCondition);
    }
}
