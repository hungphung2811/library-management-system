package lms.circulation.borrowing.domain;

/** QĐ1: hạn mức số cuốn đang mượn tối đa và số ngày mượn theo loại độc giả. Lưu ở bảng borrow_rules. */
public record BorrowRule(ReaderType readerType, int maxItems, int loanDays) {

    public BorrowRule {
        if (readerType == null) throw new IllegalArgumentException("Loại độc giả không được null");
        if (maxItems <= 0) throw new IllegalArgumentException("Hạn mức mượn phải > 0");
        if (loanDays <= 0) throw new IllegalArgumentException("Số ngày mượn phải > 0");
    }
}
