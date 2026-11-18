package lms.catalog.catalog.domain;

/** Dữ liệu tác phẩm vi phạm quy tắc (mã lỗi INVALID_WORK). */
public class InvalidWorkException extends RuntimeException {
    public InvalidWorkException(String message) {
        super(message);
    }
}
