package lms.circulation.borrowing.domain;

/** Tham số tìm kiếm phiếu mượn không hợp lệ (status lạ, page/size ngoài khoảng); trả 400. */
public class InvalidBorrowingQueryException extends RuntimeException {
    public InvalidBorrowingQueryException(String message) {
        super(message);
    }
}
