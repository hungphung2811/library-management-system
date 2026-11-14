package lms.circulation.borrowing.domain;

public enum BorrowingStatus {
    /** Còn ít nhất một bản sao chưa trả. */
    ACTIVE,
    /** Tất cả bản sao đã được trả. */
    RETURNED
}
