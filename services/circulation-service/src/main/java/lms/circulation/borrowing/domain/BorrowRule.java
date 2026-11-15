package lms.circulation.borrowing.domain;

import java.util.List;

/**
 * QĐ1, UC12: hạn mức số cuốn đang mượn tối đa và số ngày mượn theo loại độc giả của một tenant. Lưu ở bảng
 * borrow_rules (khóa (tenant_id, reader_type)).
 */
public record BorrowRule(ReaderType readerType, int maxItems, int loanDays) {

    public BorrowRule {
        if (readerType == null) throw new IllegalArgumentException("Loại độc giả không được null");
        if (maxItems <= 0) throw new IllegalArgumentException("Hạn mức mượn phải > 0");
        if (loanDays <= 0) throw new IllegalArgumentException("Số ngày mượn phải > 0");
    }

    /** Quy tắc khởi tạo cho tenant mới, cùng giá trị seed của migration circulation V4/V6. */
    public static List<BorrowRule> defaults() {
        return List.of(new BorrowRule(ReaderType.STUDENT, 5, 14), new BorrowRule(ReaderType.LECTURER, 10, 30));
    }
}
