package lms.identity.auth.domain;

/**
 * Tự đăng ký không khả dụng. MỘT mã chung cho mọi lý do (tenant không tồn tại, bị ngưng, chưa bật SALES, chưa có role
 * đăng ký mặc định) để không dò được tenant; thông điệp cố định, không chứa mã tenant.
 */
public class SignupNotAvailableException extends RuntimeException {

    public static final String MESSAGE = "Cửa hàng này không mở tự đăng ký";

    public SignupNotAvailableException() {
        super(MESSAGE);
    }
}
