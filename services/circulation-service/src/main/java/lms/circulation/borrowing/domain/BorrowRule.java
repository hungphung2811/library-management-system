package lms.circulation.borrowing.domain;

import java.util.List;

/** Hạn mức mượn theo loại độc giả của một tenant (QĐ1, UC12): số bản sao tối đa và số ngày mượn. */
public record BorrowRule(ReaderType readerType, int maxItems, int loanDays) {

    public BorrowRule {
        if (readerType == null) throw new IllegalArgumentException("readerType không được null");
        if (maxItems <= 0) throw new IllegalArgumentException("maxItems phải > 0");
        if (loanDays <= 0) throw new IllegalArgumentException("loanDays phải > 0");
    }

    /** Quy tắc khởi tạo cho tenant mới, cùng giá trị seed của migration circulation V4/V6. */
    public static List<BorrowRule> defaults() {
        return List.of(new BorrowRule(ReaderType.STUDENT, 5, 14), new BorrowRule(ReaderType.LECTURER, 10, 30));
    }
}
