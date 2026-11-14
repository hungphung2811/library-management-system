package lms.circulation.reservation.domain;

/** QĐ2: thẻ hết hạn, tài khoản bị khóa hoặc chưa có thẻ độc giả. */
public class ReaderNotEligibleException extends RuntimeException {
    public ReaderNotEligibleException(String message) {
        super(message);
    }
}
