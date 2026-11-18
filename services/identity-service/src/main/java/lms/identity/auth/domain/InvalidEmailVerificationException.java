package lms.identity.auth.domain;

/**
 * Xác minh email thất bại. MỘT thông điệp cố định cho mọi lý do (không có tài khoản, đã xác minh, OTP sai, hết hạn, hết
 * lượt thử) để không dò được tài khoản.
 */
public class InvalidEmailVerificationException extends RuntimeException {

    public static final String MESSAGE = "Mã xác minh không đúng hoặc đã hết hạn";

    public InvalidEmailVerificationException() {
        super(MESSAGE);
    }
}
