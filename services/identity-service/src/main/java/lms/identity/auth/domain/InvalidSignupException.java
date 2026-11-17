package lms.identity.auth.domain;

/** Dữ liệu đăng ký vi phạm quy tắc hồ sơ (tên đăng nhập, họ tên, email, mật khẩu). */
public class InvalidSignupException extends RuntimeException {
    public InvalidSignupException(String message) {
        super(message);
    }
}
